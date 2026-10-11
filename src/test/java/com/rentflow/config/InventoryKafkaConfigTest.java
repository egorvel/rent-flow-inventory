package com.rentflow.config;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.MethodParameter;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ExceptionClassifier;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.converter.ConversionException;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.invocation.MethodArgumentResolutionException;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

import com.rentflow.converter.ReservationCancellationEventConverter;
import com.rentflow.model.ReservationCancellationFailureCode;
import com.rentflow.model.ReservationCancellationRejectedException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryKafkaConfigTest {
    private static final String DLT = "dlt";
    private static final String PRIVATE_VALUE = "synthetic-private-value";
    private static final String TRACE_ID = "11111111111111111111111111111111";
    private static final String SPAN_ID = "2222222222222222";
    private final InventoryKafkaConfig config = new InventoryKafkaConfig();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final UUID eventId = UUID.randomUUID();
    private final ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(
            "source",
            0,
            7L,
            PRIVATE_VALUE.getBytes(StandardCharsets.UTF_8),
            ("{\"eventId\":\"" + eventId + "\",\"private\":\"" + PRIVATE_VALUE + "\"}")
                    .getBytes(StandardCharsets.UTF_8));
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final Logger logger = (Logger) LoggerFactory.getLogger(InventoryKafkaConfig.class);
    private LoggerContext context;
    private OutputStreamAppender<ILoggingEvent> appender;
    private StructuredLogEncoder encoder;
    private Level previousLevel;
    private boolean previousAdditive;

    @Mock
    private KafkaTemplate<byte[], byte[]> template;

    @Mock
    private ProducerFactory<byte[], byte[]> producerFactory;

    @BeforeEach
    void captureConfiguredJsonLogs() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.application.name", "rent-flow-inventory")
                .withProperty("logging.structured.json.stacktrace.max-length", "16384")
                .withProperty("logging.structured.json.stacktrace.max-throwable-depth", "64");
        context = new LoggerContext();
        context.putObject(Environment.class.getName(), environment);
        encoder = new StructuredLogEncoder();
        encoder.setContext(context);
        encoder.setFormat("ecs");
        encoder.start();
        appender = new OutputStreamAppender<>();
        appender.setContext(context);
        appender.setEncoder(encoder);
        appender.setOutputStream(output);
        appender.start();
        previousLevel = logger.getLevel();
        previousAdditive = logger.isAdditive();
        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
        logger.addAppender(appender);
        MDC.put("traceId", TRACE_ID);
        MDC.put("spanId", SPAN_ID);
    }

    @AfterEach
    void closeLogCapture() {
        MDC.clear();
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
        logger.setAdditive(previousAdditive);
        appender.stop();
        encoder.stop();
        context.stop();
        metrics.close();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void countsAndLogsOnlyScheduledRetriesForEverySupportedAttemptLimit(int maxAttempts) {
        confirmDltPublication();
        DefaultErrorHandler handler = handler(maxAttempts, Duration.ofMillis(1), Duration.ofMillis(8), 2);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            assertThat(handler.handleOne(new IllegalStateException(PRIVATE_VALUE), record, null, null))
                    .isEqualTo(attempt == maxAttempts);
            assertThat(counter("retry")).isEqualTo(Math.min(attempt, maxAttempts - 1));
        }
        assertThat(logs("retry").stream().map(log -> log.path("deliveryAttempt").asInt()))
                .containsExactlyElementsOf(
                        IntStream.range(1, maxAttempts).boxed().toList());
        assertThat(counter("dead_letter")).isOne();
        verify(template).send(any(ProducerRecord.class));
    }

    @ParameterizedTest
    @MethodSource("immediateAndConstantBackoffs")
    void boundsZeroAndConstantDelays(Duration initial, Duration maximum, double multiplier) {
        confirmDltPublication();
        DefaultErrorHandler handler = handler(5, initial, maximum, multiplier);
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(handler.handleOne(new IllegalStateException(), record, null, null))
                    .isEqualTo(attempt == 5);
        }
        assertThat(counter("retry")).isEqualTo(4);
        assertThat(logs("retry")).hasSize(4);
        assertThat(counter("dead_letter")).isOne();
    }

    static Stream<Arguments> immediateAndConstantBackoffs() {
        return Stream.of(
                Arguments.of(Duration.ZERO, Duration.ZERO, 2.0),
                Arguments.of(Duration.ofMillis(1), Duration.ofMillis(8), 1.0));
    }

    @ParameterizedTest
    @MethodSource("frameworkFatalExceptions")
    void recoversFrameworkFatalFailuresWithoutInventingRetries(Exception fatal) {
        confirmDltPublication();
        DefaultErrorHandler handler = handler(5, Duration.ofMillis(1), Duration.ofMillis(8), 2);
        Exception exception = new ListenerExecutionFailedException(PRIVATE_VALUE, fatal);
        assertThat(handler.handleOne(exception, record, null, null)).isTrue();
        assertThat(counter("retry")).isZero();
        assertThat(logs("retry")).isEmpty();
        assertThat(counter("dead_letter")).isOne();
    }

    static Stream<Exception> frameworkFatalExceptions() throws ReflectiveOperationException {
        List<Exception> exceptions = List.of(
                new DeserializationException(PRIVATE_VALUE, new byte[0], false, new IllegalArgumentException()),
                new MessageConversionException(PRIVATE_VALUE),
                new ConversionException(PRIVATE_VALUE, (Throwable) null),
                new MethodArgumentResolutionException(
                        MessageBuilder.withPayload(PRIVATE_VALUE).build(),
                        new MethodParameter(
                                InventoryKafkaConfigTest.class.getDeclaredMethod("fatalFixture", String.class), 0),
                        PRIVATE_VALUE),
                new NoSuchMethodException(PRIVATE_VALUE),
                new ClassCastException(PRIVATE_VALUE));
        assertThat(new HashSet<>(exceptions.stream().map(Exception::getClass).toList()))
                .isEqualTo(new HashSet<>(ExceptionClassifier.defaultFatalExceptionsList()));
        return exceptions.stream();
    }

    private static void fatalFixture(String value) {}

    @Test
    void recoversWrappedBusinessRejectionsWithoutRetry() {
        confirmDltPublication();
        DefaultErrorHandler handler = handler(5, Duration.ofMillis(1), Duration.ofMillis(8), 2);
        Exception exception = new ListenerExecutionFailedException(
                PRIVATE_VALUE,
                new ReservationCancellationRejectedException(
                        ReservationCancellationFailureCode.INVALID_EVENT, eventId));
        assertThat(handler.handleOne(exception, record, null, null)).isTrue();
        assertThat(counter("retry")).isZero();
        assertThat(logs("retry")).isEmpty();
        assertThat(logs("dead_letter"))
                .singleElement()
                .satisfies(log -> assertThat(log.path("failureCode").asText()).isEqualTo("INVALID_EVENT"));
    }

    @Test
    void preservesRetryStateResetWhenExceptionTypeChanges() {
        confirmDltPublication();
        DefaultErrorHandler handler = handler(3, Duration.ofMillis(1), Duration.ofMillis(8), 2);
        assertThat(handler.handleOne(new IllegalStateException(), record, null, null))
                .isFalse();
        assertThat(handler.handleOne(new IllegalStateException(), record, null, null))
                .isFalse();
        assertThat(handler.handleOne(new IllegalArgumentException(), record, null, null))
                .isFalse();
        assertThat(handler.handleOne(new IllegalArgumentException(), record, null, null))
                .isFalse();
        assertThat(handler.handleOne(new IllegalArgumentException(), record, null, null))
                .isTrue();
        assertThat(counter("retry")).isEqualTo(4);
        assertThat(logs("retry").stream().map(log -> log.path("deliveryAttempt").asInt()))
                .containsExactly(1, 2, 1, 2);
    }

    @Test
    void retainsDefaultOneTwoFourEightSecondSchedule() {
        DefaultErrorHandler handler = handler(5, Duration.ofSeconds(1), Duration.ofSeconds(8), 2);
        Object tracker = ReflectionTestUtils.invokeMethod(handler, "getFailureTracker");
        BackOff backoff = (BackOff) ReflectionTestUtils.getField(tracker, "backOff");
        BackOffExecution execution = backoff.start();
        assertThat(List.of(
                        execution.nextBackOff(),
                        execution.nextBackOff(),
                        execution.nextBackOff(),
                        execution.nextBackOff(),
                        execution.nextBackOff()))
                .containsExactly(1000L, 2000L, 4000L, 8000L, BackOffExecution.STOP);
        assertThat(counter("retry")).isZero();
        assertThat(logs("retry")).isEmpty();
    }

    @Test
    void retainsRecoveryCauseMessagesWithoutDumpingRecordsAndRestartsRetriesAfterPublicationFailure() {
        configureDltProducer();
        AtomicBoolean failPublication = new AtomicBoolean(true);
        doAnswer(invocation -> failPublication.getAndSet(false)
                        ? CompletableFuture.failedFuture(new IllegalStateException("Broker acknowledgement timed out"))
                        : confirmedSend(invocation.getArgument(0)))
                .when(template)
                .send(any(ProducerRecord.class));
        DefaultErrorHandler handler = handler(2, Duration.ZERO, Duration.ZERO, 2);
        assertThat(handler.handleOne(new IllegalArgumentException("Processing failed"), record, null, null))
                .isFalse();
        assertThat(handler.handleOne(new IllegalArgumentException("Processing failed"), record, null, null))
                .isFalse();
        assertThat(counter("dead_letter_publish_failure")).isOne();
        assertThat(counter("dead_letter")).isZero();
        assertThat(handler.handleOne(new IllegalArgumentException("Processing failed"), record, null, null))
                .isFalse();
        assertThat(handler.handleOne(new IllegalArgumentException("Processing failed"), record, null, null))
                .isTrue();
        assertThat(counter("retry")).isEqualTo(2);
        assertThat(counter("dead_letter")).isOne();
        assertThat(logs("retry")).hasSize(2).allSatisfy(log -> {
            assertThat(log.path("log").path("level").asText()).isEqualTo("WARN");
            assertThat(log.path("deliveryAttempt").asInt()).isOne();
            assertThat(log.path("failureCode").asText()).isEqualTo("RETRYABLE_PROCESSING_FAILURE");
            assertThat(log.path("error").path("message").asText()).isEqualTo("Processing failed");
        });
        assertThat(logs("dead_letter_publish_failure")).singleElement().satisfies(log -> {
            assertThat(log.path("log").path("level").asText()).isEqualTo("ERROR");
            assertThat(log.path("exceptionType").asText()).isEqualTo("org.springframework.kafka.KafkaException");
            assertThat(log.path("error").path("stack_trace").asText())
                    .contains("IllegalStateException", "InventoryKafkaConfigTest", "Broker acknowledgement timed out");
            assertThat(log.path("error").path("message").asText()).isNotBlank();
        });
        assertThat(logs("dead_letter")).singleElement().satisfies(log -> {
            assertThat(log.path("log").path("level").asText()).isEqualTo("ERROR");
            assertThat(log.path("error").path("message").asText()).isEqualTo("Processing failed");
        });
        for (JsonNode log : logs(null)) {
            assertThat(log.path("eventId").asText()).isEqualTo(eventId.toString());
            assertThat(log.path("sourceTopic").asText()).isEqualTo("source");
            assertThat(log.path("partition").asInt()).isZero();
            assertThat(log.path("offset").asLong()).isEqualTo(7);
            assertThat(log.path("traceId").asText()).isEqualTo(TRACE_ID);
            assertThat(log.path("spanId").asText()).isEqualTo(SPAN_ID);
        }
        assertThat(output.toString(StandardCharsets.UTF_8)).doesNotContain(PRIVATE_VALUE, "\"private\"");
    }

    @Test
    void malformedRecordDiagnosticsDoNotEchoRawInputOrParserMessages() {
        confirmDltPublication();
        DefaultErrorHandler handler = handler(5, Duration.ZERO, Duration.ZERO, 2);
        ConsumerRecord<byte[], byte[]> malformed = new ConsumerRecord<>(
                "source",
                0,
                8L,
                PRIVATE_VALUE.getBytes(StandardCharsets.UTF_8),
                ("{\"unknown\":\"" + PRIVATE_VALUE + "\",broken").getBytes(StandardCharsets.UTF_8));
        ReservationCancellationEventConverter converter = new ReservationCancellationEventConverter(4096);

        assertThatThrownBy(() -> converter.convert(malformed))
                .isInstanceOfSatisfying(ReservationCancellationRejectedException.class, exception -> {
                    assertThat(exception.getCause()).isNull();
                    assertThat(handler.handleOne(exception, malformed, null, null))
                            .isTrue();
                });
        assertThat(logs("dead_letter")).singleElement().satisfies(log -> {
            assertThat(log.path("failureCode").asText()).isEqualTo("INVALID_EVENT");
            assertThat(log.path("error").path("type").asText())
                    .isEqualTo(ReservationCancellationRejectedException.class.getName());
        });
        assertThat(output.toString(StandardCharsets.UTF_8)).doesNotContain(PRIVATE_VALUE, "unknown", "broken");
        assertThat(counter("retry")).isZero();
    }

    private DefaultErrorHandler handler(int maxAttempts, Duration initial, Duration maximum, double multiplier) {
        ReservationCancellationProperties properties = new ReservationCancellationProperties(
                "source",
                DLT,
                4096,
                new ReservationCancellationProperties.Retry(maxAttempts, initial, multiplier, maximum));
        return config.reservationCancellationErrorHandler(
                template, properties, new ReservationCancellationEventConverter(4096), metrics, "group");
    }

    private void confirmDltPublication() {
        configureDltProducer();
        when(template.send(any(ProducerRecord.class)))
                .thenAnswer(invocation -> confirmedSend(invocation.getArgument(0)));
    }

    private void configureDltProducer() {
        when(template.getProducerFactory()).thenReturn(producerFactory);
        when(producerFactory.getConfigurationProperties()).thenReturn(Map.of());
    }

    private CompletableFuture<SendResult<byte[], byte[]>> confirmedSend(ProducerRecord<byte[], byte[]> publication) {
        return CompletableFuture.completedFuture(
                new SendResult<>(publication, new RecordMetadata(new TopicPartition(DLT, 0), 0L, 0, 0L, 0, 0)));
    }

    private double counter(String outcome) {
        return metrics.counter("inventory.reservation.cancellation.records", "outcome", outcome)
                .count();
    }

    private List<JsonNode> logs(String outcome) {
        List<JsonNode> events = new ArrayList<>();
        JsonMapper mapper = JsonMapper.builder().build();
        for (String line : output.toString(StandardCharsets.UTF_8).lines().toList()) {
            JsonNode event = mapper.readTree(line);
            if (event.path("log").path("logger").asText().equals(InventoryKafkaConfig.class.getName())
                    && (outcome == null || event.path("outcome").asText().equals(outcome))) {
                events.add(event);
            }
        }
        return events;
    }
}
