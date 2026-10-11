package com.rentflow;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.kafka.KafkaContainer;

import com.rentflow.model.InventoryItem;
import com.rentflow.model.InventoryReservationCancellationOutcome;
import com.rentflow.model.InventoryStatus;
import com.rentflow.model.InventoryStatusTransition;
import com.rentflow.repository.InventoryRepository;
import com.rentflow.repository.InventoryStatusHistoryRepository;
import com.rentflow.repository.ReservationCancellationInboxRepository;
import com.rentflow.service.InventoryService;
import com.rentflow.support.PostgresIntegrationTest;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "management.tracing.sampling.probability=1.0",
            "spring.kafka.consumer.group-id=reservation-cancellation-kafka-it",
            "spring.kafka.listener.concurrency=1",
            "inventory.kafka.reservation-cancellation.topic=reservation-cancellation-source-it",
            "inventory.kafka.reservation-cancellation.dead-letter-topic=reservation-cancellation-dlt-it",
            "inventory.kafka.reservation-cancellation.retry.max-attempts=5",
            "inventory.kafka.reservation-cancellation.retry.initial-backoff=10ms",
            "inventory.kafka.reservation-cancellation.retry.multiplier=2",
            "inventory.kafka.reservation-cancellation.retry.max-backoff=80ms"
        })
@Import(ReservationCancellationKafkaIT.KafkaTopics.class)
@AutoConfigureTracing
@ExtendWith(OutputCaptureExtension.class)
@MockitoSpyBean(types = KafkaTemplate.class, name = "kafkaTemplate")
class ReservationCancellationKafkaIT extends PostgresIntegrationTest {
    private static final InMemorySpanExporter SPANS = InMemorySpanExporter.create();
    private static final String SOURCE = "reservation-cancellation-source-it";
    private static final String DLT = "reservation-cancellation-dlt-it";
    private static final String GROUP = "reservation-cancellation-kafka-it";

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer("apache/kafka:4.3.1").withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    @DynamicPropertySource
    static void registerKafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    private KafkaTemplate<byte[], byte[]> kafka;

    @MockitoSpyBean
    private InventoryService service;

    @Autowired
    private Tracer tracer;

    @Autowired
    private SdkTracerProvider tracerProvider;

    @Autowired
    private InventoryRepository inventory;

    @Autowired
    private InventoryStatusHistoryRepository history;

    @Autowired
    private ReservationCancellationInboxRepository inbox;

    @Autowired
    private MeterRegistry metrics;

    @BeforeEach
    void resetDatabase() {
        inbox.deleteAllInBatch();
        history.deleteAllInBatch();
        inventory.deleteAllInBatch();
        tracerProvider.forceFlush().join(5, TimeUnit.SECONDS);
        SPANS.reset();
    }

    @Test
    void correlatesProducerRetryAndCommittedReleaseWithOneOriginatingTrace(CapturedOutput output) throws Exception {
        String serial = "KAFKA-TRACED-RETRY";
        UUID eventId = UUID.randomUUID();
        inventory.saveAndFlush(new InventoryItem(serial, "Drill", "Kafka", InventoryStatus.RESERVED));
        doThrow(new IllegalStateException("Equipment processing failed"))
                .doCallRealMethod()
                .when(service)
                .releaseAfterCancellation(argThat(event -> event.eventId().equals(eventId)));
        double retries = counter("retry");
        io.micrometer.tracing.Span origin =
                tracer.nextSpan().name("reservation.cancel").start();
        String traceId = origin.context().traceId();
        org.springframework.kafka.support.SendResult<byte[], byte[]> sent;
        try (Tracer.SpanInScope scope = tracer.withSpan(origin)) {
            sent = kafka.send(SOURCE, bytes(serial), event(eventId, serial, "2026-09-15T15:30:00Z"))
                    .get();
        } finally {
            origin.end();
        }
        await(() -> inbox.findById(eventId).isPresent());
        await(() -> counter("retry") == retries + 1);
        await(() -> committedOffset(
                        new TopicPartition(SOURCE, sent.getRecordMetadata().partition()))
                >= sent.getRecordMetadata().offset() + 1);
        tracerProvider.forceFlush().join(5, TimeUnit.SECONDS);
        java.util.List<SpanData> spans = SPANS.getFinishedSpanItems().stream()
                .filter(span -> span.getTraceId().equals(traceId))
                .toList();
        assertThat(spans.stream().filter(span -> span.getKind() == io.opentelemetry.api.trace.SpanKind.CONSUMER))
                .hasSize(2);
        assertThat(spans)
                .anySatisfy(span -> assertThat(span.getEvents().toString())
                        .contains("exception.message", "Equipment processing failed"));
        assertThat(inventory.findById(serial).orElseThrow().getStatus()).isEqualTo(InventoryStatus.AVAILABLE);
        assertThat(history.count()).isOne();
        assertThat(kafkaLogs(output, eventId, "retry")).singleElement().satisfies(log -> {
            assertThat(log.path("traceId").asText()).isEqualTo(traceId);
            assertThat(spans.stream()
                            .filter(span -> span.getKind() == io.opentelemetry.api.trace.SpanKind.CONSUMER)
                            .map(SpanData::getSpanId))
                    .contains(log.path("spanId").asText());
            assertThat(log.path("deliveryAttempt").asInt()).isOne();
            assertThat(log.path("failureCode").asText()).isEqualTo("RETRYABLE_PROCESSING_FAILURE");
            assertThat(log.path("log").path("level").asText()).isEqualTo("WARN");
            assertThat(log.path("partition").asInt())
                    .isEqualTo(sent.getRecordMetadata().partition());
            assertThat(log.path("offset").asLong())
                    .isEqualTo(sent.getRecordMetadata().offset());
            assertThat(log.path("error").path("stack_trace").asText()).contains("Equipment processing failed");
            assertThat(log.toString())
                    .doesNotContain(new String(event(eventId, serial, "2026-09-15T15:30:00Z"), StandardCharsets.UTF_8));
        });
    }

    @Test
    void failedDltPublicationRetainsOffsetUntilConfirmedRecovery(CapturedOutput output) throws Exception {
        String serial = "KAFKA-DLT-FAILURE";
        UUID eventId = UUID.randomUUID();
        java.util.concurrent.atomic.AtomicBoolean failSend = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(invocation -> failSend.get()
                        ? java.util.concurrent.CompletableFuture.failedFuture(
                                new IllegalStateException("Broker acknowledgement timed out"))
                        : invocation.callRealMethod())
                .when(kafka)
                .send(argThat((org.apache.kafka.clients.producer.ProducerRecord<byte[], byte[]> record) ->
                        record.topic().equals(DLT)));
        double failures = counter("dead_letter_publish_failure");
        double recovered = counter("dead_letter");
        io.micrometer.tracing.Span origin =
                tracer.nextSpan().name("reservation.cancel").start();
        String traceId = origin.context().traceId();
        org.springframework.kafka.support.SendResult<byte[], byte[]> sent;
        try (Tracer.SpanInScope scope = tracer.withSpan(origin)) {
            sent = kafka.send(SOURCE, bytes(serial), event(eventId, serial, "2026-09-15T15:30:00Z"))
                    .get();
        } finally {
            origin.end();
        }
        TopicPartition partition =
                new TopicPartition(SOURCE, sent.getRecordMetadata().partition());
        await(() -> counter("dead_letter_publish_failure") > failures);
        assertThat(committedOffset(partition))
                .isLessThan(sent.getRecordMetadata().offset() + 1);
        assertThat(inbox.findById(eventId)).isEmpty();
        failSend.set(false);
        ConsumerRecord<byte[], byte[]> deadLetter = consumeDlt(bytes(serial));
        assertThat(header(deadLetter, "rentflow.dlt.event-id")).isEqualTo(eventId.toString());
        await(() -> counter("dead_letter") > recovered);
        await(() -> committedOffset(partition) >= sent.getRecordMetadata().offset() + 1);
        tracerProvider.forceFlush().join(5, TimeUnit.SECONDS);
        List<String> consumerSpanIds = SPANS.getFinishedSpanItems().stream()
                .filter(span -> span.getTraceId().equals(traceId))
                .filter(span -> span.getKind() == io.opentelemetry.api.trace.SpanKind.CONSUMER)
                .map(SpanData::getSpanId)
                .toList();
        assertThat(kafkaLogs(output, eventId, "dead_letter_publish_failure"))
                .isNotEmpty()
                .allSatisfy(log -> {
                    assertThat(log.path("log").path("level").asText()).isEqualTo("ERROR");
                    assertThat(log.path("traceId").asText()).isEqualTo(traceId);
                    assertThat(consumerSpanIds).contains(log.path("spanId").asText());
                    assertThat(log.path("partition").asInt()).isEqualTo(partition.partition());
                    assertThat(log.path("offset").asLong())
                            .isEqualTo(sent.getRecordMetadata().offset());
                    assertThat(log.path("error").path("stack_trace").asText())
                            .contains(
                                    "IllegalStateException",
                                    "ReservationCancellationKafkaIT",
                                    "Broker acknowledgement timed out");
                    assertThat(log.path("error").path("message").asText()).isNotBlank();
                });
        assertThat(kafkaLogs(output, eventId, "dead_letter")).singleElement().satisfies(log -> {
            assertThat(log.path("traceId").asText()).isEqualTo(traceId);
            assertThat(consumerSpanIds).contains(log.path("spanId").asText());
        });
    }

    @Test
    void malformedTraceContextDoesNotRejectTheBusinessEvent() throws Exception {
        String serial = "KAFKA-MALFORMED-TRACE";
        UUID eventId = UUID.randomUUID();
        inventory.saveAndFlush(new InventoryItem(serial, "Drill", "Kafka", InventoryStatus.RESERVED));
        Map<String, Object> properties = Map.of(
                org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                        org.apache.kafka.common.serialization.ByteArraySerializer.class,
                org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                        org.apache.kafka.common.serialization.ByteArraySerializer.class);
        try (org.apache.kafka.clients.producer.KafkaProducer<byte[], byte[]> producer =
                new org.apache.kafka.clients.producer.KafkaProducer<>(properties)) {
            org.apache.kafka.clients.producer.ProducerRecord<byte[], byte[]> record =
                    new org.apache.kafka.clients.producer.ProducerRecord<>(
                            SOURCE, bytes(serial), event(eventId, serial, "2026-09-15T15:30:00Z"));
            record.headers().add("traceparent", bytes("malformed"));
            record.headers().add("baggage", bytes("customer=synthetic-private-value"));
            producer.send(record).get(5, TimeUnit.SECONDS);
        }
        await(() -> inbox.findById(eventId).isPresent());
        assertThat(inventory.findById(serial).orElseThrow().getStatus()).isEqualTo(InventoryStatus.AVAILABLE);
        assertThat(history.count()).isOne();
    }

    @ParameterizedTest
    @ValueSource(strings = {"valid", "missing", "malformed"})
    void frameworkPropagatesUsableContextAndDisablesBaggage(String variant) throws Exception {
        String serial = "KAFKA-CONTEXT-" + variant;
        UUID eventId = UUID.randomUUID();
        inventory.saveAndFlush(new InventoryItem(serial, "Drill", "Kafka", InventoryStatus.RESERVED));
        AtomicReference<String> consumerSpanId = new AtomicReference<>();
        AtomicReference<Boolean> baggageEmpty = new AtomicReference<>();
        doAnswer(invocation -> {
                    io.micrometer.tracing.Span span = tracer.currentSpan();
                    assertThat(span).isNotNull();
                    consumerSpanId.set(span.context().spanId());
                    baggageEmpty.set(Baggage.current().asMap().isEmpty());
                    return invocation.callRealMethod();
                })
                .when(service)
                .releaseAfterCancellation(argThat(event -> event.eventId().equals(eventId)));
        String parent = "00-11111111111111111111111111111111-2222222222222222-01";
        org.apache.kafka.clients.producer.ProducerRecord<byte[], byte[]> record =
                new org.apache.kafka.clients.producer.ProducerRecord<>(
                        SOURCE, 0, bytes(serial), event(eventId, serial, "2026-09-15T15:30:00Z"));
        switch (variant) {
            case "valid" -> record.headers().add("traceparent", bytes(parent));
            case "malformed" -> record.headers().add("traceparent", bytes("invalid"));
            case "missing" -> {}
            default -> throw new AssertionError("Unknown context variant");
        }
        record.headers().add("tracestate", bytes("vendor=state"));
        record.headers().add("baggage", bytes("customer=synthetic-private-value"));
        Map<String, Object> properties = Map.of(
                org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                        org.apache.kafka.common.serialization.ByteArraySerializer.class,
                org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                        org.apache.kafka.common.serialization.ByteArraySerializer.class);
        org.apache.kafka.clients.producer.RecordMetadata sent;
        try (org.apache.kafka.clients.producer.KafkaProducer<byte[], byte[]> producer =
                new org.apache.kafka.clients.producer.KafkaProducer<>(properties)) {
            sent = producer.send(record).get(5, TimeUnit.SECONDS);
        }
        await(() -> committedOffset(new TopicPartition(SOURCE, sent.partition())) >= sent.offset() + 1);
        assertThat(consumerSpanId.get()).isNotNull();
        assertThat(baggageEmpty.get()).isTrue();
        tracerProvider.forceFlush().join(5, TimeUnit.SECONDS);
        SpanData consumer = SPANS.getFinishedSpanItems().stream()
                .filter(span -> span.getSpanId().equals(consumerSpanId.get()))
                .findFirst()
                .orElseThrow();
        if (variant.equals("valid")) {
            assertThat(consumer.getTraceId()).isEqualTo("11111111111111111111111111111111");
            assertThat(consumer.getParentSpanId()).isEqualTo("2222222222222222");
            assertThat(consumer.getSpanContext().getTraceState().get("vendor")).isEqualTo("state");
        } else {
            assertThat(consumer.getTraceId()).isNotEqualTo("11111111111111111111111111111111");
            assertThat(consumer.getParentSpanId()).isEqualTo("0000000000000000");
        }
        assertThat(inbox.findById(eventId)).isPresent();
        assertThat(inventory.findById(serial).orElseThrow().getStatus()).isEqualTo(InventoryStatus.AVAILABLE);
        assertThat(history.count()).isOne();
        assertThat(consumer.getAttributes().toString() + consumer.getEvents())
                .doesNotContain("synthetic-private-value");
    }

    @Test
    void frameworkFatalProcessingFailureGoesStraightToDltWithoutRetryDiagnostics(CapturedOutput output)
            throws Exception {
        String serial = "KAFKA-FATAL-PROCESSING";
        UUID eventId = UUID.randomUUID();
        inventory.saveAndFlush(new InventoryItem(serial, "Drill", "Kafka", InventoryStatus.RESERVED));
        doThrow(new ClassCastException("Unexpected equipment state"))
                .when(service)
                .releaseAfterCancellation(argThat(event -> event.eventId().equals(eventId)));
        double retries = counter("retry");
        org.springframework.kafka.support.SendResult<byte[], byte[]> sent = kafka.send(
                        SOURCE, bytes(serial), event(eventId, serial, "2026-09-15T15:30:00Z"))
                .get();
        ConsumerRecord<byte[], byte[]> deadLetter = consumeDlt(bytes(serial));
        await(() -> committedOffset(
                        new TopicPartition(SOURCE, sent.getRecordMetadata().partition()))
                >= sent.getRecordMetadata().offset() + 1);
        assertThat(counter("retry")).isEqualTo(retries);
        assertThat(kafkaLogs(output, eventId, "retry")).isEmpty();
        assertThat(kafkaLogs(output, eventId, "dead_letter"))
                .singleElement()
                .satisfies(log ->
                        assertThat(log.path("log").path("level").asText()).isEqualTo("ERROR"));
        assertThat(inbox.findById(eventId)).isEmpty();
        assertThat(history.count()).isZero();
        assertThat(inventory.findById(serial).orElseThrow().getStatus()).isEqualTo(InventoryStatus.RESERVED);
        assertDltTrace(deadLetter, 1);
    }

    @Test
    void consumesAReleaseAndCommitsItsOffsetOnlyAfterTheDatabaseEffect() throws Exception {
        String serial = "KAFKA-RELEASE";
        UUID eventId = UUID.randomUUID();
        inventory.saveAndFlush(new InventoryItem(serial, "Drill", "Kafka", InventoryStatus.RESERVED));
        byte[] value = event(eventId, serial, "2026-09-15T15:30:00Z");

        org.springframework.kafka.support.SendResult<byte[], byte[]> sent =
                kafka.send(SOURCE, bytes(serial), value).get();
        TopicPartition sourcePartition =
                new TopicPartition(SOURCE, sent.getRecordMetadata().partition());

        await(() -> inbox.findById(eventId).isPresent());
        assertThat(inventory.findById(serial).orElseThrow().getStatus()).isEqualTo(InventoryStatus.AVAILABLE);
        assertThat(history.findAll()).singleElement().satisfies(entry -> {
            assertThat(entry.getStatusFrom()).isEqualTo(InventoryStatus.RESERVED);
            assertThat(entry.getStatusTo()).isEqualTo(InventoryStatus.AVAILABLE);
        });
        assertThat(inbox.findById(eventId).orElseThrow().getOutcome())
                .isEqualTo(InventoryReservationCancellationOutcome.RELEASED);
        await(() -> committedOffset(sourcePartition) >= sent.getRecordMetadata().offset() + 1);
    }

    @Test
    void suppressesAFormattingEquivalentDuplicateAfterTheItemIsReservedAgain() throws Exception {
        String serial = "KAFKA-DUPLICATE";
        UUID eventId = UUID.randomUUID();
        inventory.saveAndFlush(new InventoryItem(serial, "Drill", "Kafka", InventoryStatus.RESERVED));
        byte[] original = event(eventId, serial, "2026-09-15T15:30:00Z");
        kafka.send(SOURCE, bytes(serial), original).get();
        await(() -> inbox.findById(eventId).isPresent());
        service.transitionStatus(java.util.List.of(new InventoryStatusTransition(serial, InventoryStatus.RESERVED)));
        double duplicatesBefore = counter("duplicate");
        String equivalent = """
                {"extra":"ignored","serialNumber":"%s","occurredAt":"2026-09-15T15:30:00.000Z",
                 "eventVersion":1,"eventType":"ReservationCancelled","eventId":"%s"}
                """.formatted(serial, eventId.toString().toUpperCase(java.util.Locale.ROOT));

        kafka.send(SOURCE, bytes(serial), bytes(equivalent)).get();
        await(() -> counter("duplicate") == duplicatesBefore + 1);

        assertThat(inventory.findById(serial).orElseThrow().getStatus()).isEqualTo(InventoryStatus.RESERVED);
        assertThat(history.count()).isEqualTo(2);
        assertThat(inbox.count()).isOne();
    }

    @Test
    void sendsDeterministicBusinessFailureToTheSameDltPartitionWithoutRetry() throws Exception {
        String serial = "KAFKA-MISSING";
        UUID eventId = UUID.randomUUID();
        byte[] key = bytes(serial);
        byte[] value = event(eventId, serial, "2026-09-15T15:30:00Z");
        double retriesBefore = counter("retry");
        org.springframework.kafka.support.SendResult<byte[], byte[]> sent =
                kafka.send(SOURCE, key, value).get();

        ConsumerRecord<byte[], byte[]> deadLetter = consumeDlt(key);

        assertThat(deadLetter.partition()).isEqualTo(sent.getRecordMetadata().partition());
        assertThat(deadLetter.key()).isEqualTo(key);
        assertThat(deadLetter.value()).isEqualTo(value);
        assertThat(header(deadLetter, "rentflow.dlt.failure-code")).isEqualTo("INVENTORY_ITEM_NOT_FOUND");
        assertThat(header(deadLetter, "rentflow.dlt.event-id")).isEqualTo(eventId.toString());
        assertThat(deadLetter.headers().toArray())
                .extracting(Header::key)
                .containsExactlyInAnyOrder(
                        "rentflow.dlt.source-topic",
                        "rentflow.dlt.source-partition",
                        "rentflow.dlt.source-offset",
                        "rentflow.dlt.consumer-group",
                        "rentflow.dlt.failure-code",
                        "rentflow.dlt.failed-at",
                        "rentflow.dlt.event-id",
                        "traceparent");
        assertThat(counter("retry")).isEqualTo(retriesBefore);
        assertThat(inbox.count()).isZero();
        assertThat(history.count()).isZero();
        TopicPartition sourcePartition =
                new TopicPartition(SOURCE, sent.getRecordMetadata().partition());
        await(() -> committedOffset(sourcePartition) >= sent.getRecordMetadata().offset() + 1);
        assertDltTrace(deadLetter, 1);
    }

    @Test
    void preservesTraceAcrossAllAttemptsAndRetryExhaustion() throws Exception {
        String serial = "KAFKA-RETRIES-EXHAUSTED";
        UUID eventId = UUID.randomUUID();
        inventory.saveAndFlush(new InventoryItem(serial, "Drill", "Kafka", InventoryStatus.RESERVED));
        doThrow(new IllegalStateException("Equipment processing failed"))
                .when(service)
                .releaseAfterCancellation(argThat(event -> event.eventId().equals(eventId)));
        double retriesBefore = counter("retry");
        org.springframework.kafka.support.SendResult<byte[], byte[]> sent = kafka.send(
                        SOURCE, bytes(serial), event(eventId, serial, "2026-09-15T15:30:00Z"))
                .get();

        ConsumerRecord<byte[], byte[]> deadLetter = consumeDlt(bytes(serial));
        assertThat(header(deadLetter, "rentflow.dlt.failure-code")).isEqualTo("PROCESSING_RETRIES_EXHAUSTED");
        TopicPartition sourcePartition =
                new TopicPartition(SOURCE, sent.getRecordMetadata().partition());
        await(() -> committedOffset(sourcePartition) >= sent.getRecordMetadata().offset() + 1);
        assertThat(counter("retry")).isEqualTo(retriesBefore + 4);
        assertThat(inbox.findById(eventId)).isEmpty();
        assertThat(history.count()).isZero();
        assertThat(inventory.findById(serial).orElseThrow().getStatus()).isEqualTo(InventoryStatus.RESERVED);
        assertDltTrace(deadLetter, 5);
    }

    @Test
    void quarantinesAnIdentifierConflictAndKeepsTheOriginalReceipt() throws Exception {
        String serial = "KAFKA-CONFLICT";
        UUID eventId = UUID.randomUUID();
        inventory.saveAndFlush(new InventoryItem(serial, "Drill", "Kafka", InventoryStatus.AVAILABLE));
        kafka.send(SOURCE, bytes(serial), event(eventId, serial, "2026-09-15T15:30:00Z"))
                .get();
        await(() -> inbox.findById(eventId).isPresent());
        String originalFingerprint = inbox.findById(eventId).orElseThrow().getFingerprint();

        kafka.send(SOURCE, bytes(serial), event(eventId, serial, "2026-09-15T15:30:01Z"))
                .get();
        ConsumerRecord<byte[], byte[]> deadLetter = consumeDlt(bytes(serial));

        assertThat(header(deadLetter, "rentflow.dlt.failure-code")).isEqualTo("EVENT_ID_REUSED");
        assertThat(inbox.findById(eventId).orElseThrow().getFingerprint()).isEqualTo(originalFingerprint);
        assertThat(inbox.count()).isOne();
        assertThat(history.count()).isZero();
    }

    private void assertDltTrace(ConsumerRecord<byte[], byte[]> deadLetter, int attempts) {
        tracerProvider.forceFlush().join(5, TimeUnit.SECONDS);
        String[] context = header(deadLetter, "traceparent").split("-");
        java.util.List<SpanData> spans = SPANS.getFinishedSpanItems().stream()
                .filter(span -> span.getTraceId().equals(context[1]))
                .toList();
        java.util.List<SpanData> consumers = spans.stream()
                .filter(span -> span.getKind() == io.opentelemetry.api.trace.SpanKind.CONSUMER)
                .toList();
        assertThat(consumers).hasSize(attempts);
        SpanData publication = spans.stream()
                .filter(span -> span.getSpanId().equals(context[2]))
                .findFirst()
                .orElseThrow();
        assertThat(publication.getKind()).isEqualTo(io.opentelemetry.api.trace.SpanKind.PRODUCER);
        assertThat(consumers).extracting(SpanData::getSpanId).contains(publication.getParentSpanId());
        assertThat(spans)
                .allSatisfy(span -> assertThat(span.getAttributes().toString() + span.getEvents())
                        .doesNotContain(new String(deadLetter.value(), StandardCharsets.UTF_8)));
    }

    private List<JsonNode> kafkaLogs(CapturedOutput output, UUID eventId, String outcome) {
        JsonMapper mapper = JsonMapper.builder().build();
        return output.getOut()
                .lines()
                .filter(line -> line.startsWith("{"))
                .map(mapper::readTree)
                .filter(log ->
                        log.path("log").path("logger").asText().equals("com.rentflow.config.InventoryKafkaConfig"))
                .filter(log -> log.path("eventId").asText().equals(eventId.toString()))
                .filter(log -> log.path("outcome").asText().equals(outcome))
                .toList();
    }

    private long committedOffset(TopicPartition partition) {
        try (AdminClient admin =
                AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            org.apache.kafka.clients.consumer.OffsetAndMetadata offset = admin.listConsumerGroupOffsets(GROUP)
                    .partitionsToOffsetAndMetadata()
                    .get(5, TimeUnit.SECONDS)
                    .get(partition);
            return offset == null ? -1 : offset.offset();
        } catch (Exception exception) {
            return -1;
        }
    }

    private ConsumerRecord<byte[], byte[]> consumeDlt(byte[] expectedKey) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(Set.of(DLT));
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<byte[], byte[]> records = consumer.poll(Duration.ofMillis(200));
                for (ConsumerRecord<byte[], byte[]> record : records) {
                    if (java.util.Arrays.equals(record.key(), expectedKey)) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("No dead-letter record received");
    }

    private String header(ConsumerRecord<byte[], byte[]> record, String name) {
        Header header = record.headers().lastHeader(name);
        assertThat(header).as(name).isNotNull();
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private double counter(String outcome) {
        return metrics.counter("inventory.reservation.cancellation.records", "outcome", outcome)
                .count();
    }

    private byte[] event(UUID eventId, String serial, String occurredAt) {
        return bytes("""
                {"eventId":"%s","eventType":"ReservationCancelled","eventVersion":1,
                 "occurredAt":"%s","serialNumber":"%s"}
                """.formatted(eventId, occurredAt, serial));
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while awaiting Kafka processing", exception);
            }
        }
        throw new AssertionError("Condition was not met before timeout");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class KafkaTopics {
        @Bean
        SpanExporter recordingSpanExporter() {
            return SPANS;
        }

        @Bean
        NewTopic cancellationSourceTopic() {
            return TopicBuilder.name(SOURCE)
                    .partitions(3)
                    .replicas(1)
                    .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                    .config(TopicConfig.RETENTION_MS_CONFIG, "604800000")
                    .build();
        }

        @Bean
        NewTopic cancellationDltTopic() {
            return TopicBuilder.name(DLT)
                    .partitions(3)
                    .replicas(1)
                    .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                    .config(TopicConfig.RETENTION_MS_CONFIG, "2592000000")
                    .build();
        }
    }
}
