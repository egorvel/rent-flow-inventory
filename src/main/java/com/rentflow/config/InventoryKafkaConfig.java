package com.rentflow.config;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.ExponentialBackOff;

import com.rentflow.converter.ReservationCancellationEventConverter;
import com.rentflow.model.ReservationCancellationFailureCode;
import com.rentflow.model.ReservationCancellationProcessingException;
import com.rentflow.model.ReservationCancellationRejectedException;
import com.rentflow.util.KafkaTraceConsumerInterceptor;

import io.micrometer.core.instrument.MeterRegistry;

@Configuration
@EnableKafka
@EnableConfigurationProperties(ReservationCancellationProperties.class)
public class InventoryKafkaConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(InventoryKafkaConfig.class);
    private static final String METRIC_NAME = "inventory.reservation.cancellation.records";
    private static final List<String> DLT_HEADERS = List.of(
            "rentflow.dlt.source-topic",
            "rentflow.dlt.source-partition",
            "rentflow.dlt.source-offset",
            "rentflow.dlt.consumer-group",
            "rentflow.dlt.failure-code",
            "rentflow.dlt.failed-at",
            "rentflow.dlt.event-id");

    @Bean
    DefaultKafkaConsumerFactoryCustomizer normalizeKafkaTraceBeforeObservation() {
        return factory -> {
            String name = ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG;
            Object configured = factory.getConfigurationProperties().get(name);
            List<?> existing = configured == null
                    ? List.of()
                    : (List<?>) ConfigDef.parseType(name, configured, ConfigDef.Type.LIST);
            List<Object> interceptors = new ArrayList<>(existing);
            String normalizer = KafkaTraceConsumerInterceptor.class.getName();
            interceptors.removeIf(interceptor ->
                    normalizer.equals(interceptor) || KafkaTraceConsumerInterceptor.class.equals(interceptor));
            interceptors.add(normalizer);
            factory.updateConfigs(Map.of(name, interceptors));
        };
    }

    @Bean
    DefaultErrorHandler reservationCancellationErrorHandler(
            KafkaTemplate<byte[], byte[]> template,
            ReservationCancellationProperties properties,
            ReservationCancellationEventConverter converter,
            MeterRegistry metrics,
            @Value("${spring.kafka.consumer.group-id}") String consumerGroup) {
        DeadLetterPublishingRecoverer recoverer =
                new BoundedHeadersDeadLetterRecoverer(template, properties.deadLetterTopic(), consumerGroup, converter);
        recoverer.setFailIfSendResultIsError(true);

        ReservationCancellationProperties.Retry retry = properties.retry();
        ExponentialBackOff backOff = new ExponentialBackOff();
        backOff.setMaxAttempts(retry.maxAttempts() - 1);
        backOff.setInitialInterval(retry.initialBackoff().toMillis());
        backOff.setMultiplier(retry.multiplier());
        backOff.setMaxInterval(retry.maxBackoff().toMillis());

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        errorHandler.addNotRetryableExceptions(ReservationCancellationRejectedException.class);
        errorHandler.setAckAfterHandle(true);
        errorHandler.setBackOffFunction(
                (record, exception) -> observedRetryBackOff(backOff, record, exception, converter, metrics));
        errorHandler.setRetryListeners(new RetryListener() {
            @Override
            public void failedDelivery(ConsumerRecord<?, ?> record, Exception exception, int deliveryAttempt) {
                // Retry scheduling is observed only after the backoff decision, not every failure.
            }

            @Override
            public void recovered(ConsumerRecord<?, ?> record, Exception exception) {
                count(metrics, "dead_letter");
                ReservationCancellationFailureCode failureCode = failureCode(exception);
                Optional<UUID> eventId = eventId(exception, record.value(), converter);
                LOGGER.atError()
                        .addKeyValue("outcome", "dead_letter")
                        .addKeyValue("sourceTopic", record.topic())
                        .addKeyValue("partition", record.partition())
                        .addKeyValue("offset", record.offset())
                        .addKeyValue("failureCode", failureCode.name())
                        .addKeyValue("eventId", eventId.map(UUID::toString).orElse("unavailable"))
                        .log("Reservation cancellation sent to DLT");
            }

            @Override
            public void recoveryFailed(ConsumerRecord<?, ?> record, Exception original, Exception recoveryFailure) {
                count(metrics, "dead_letter_publish_failure");
                LOGGER.atError()
                        .addKeyValue("outcome", "dead_letter_publish_failure")
                        .addKeyValue("sourceTopic", record.topic())
                        .addKeyValue("partition", record.partition())
                        .addKeyValue("offset", record.offset())
                        .addKeyValue("failureCode", failureCode(original).name())
                        .addKeyValue(
                                "eventId",
                                eventId(original, record.value(), converter)
                                        .map(UUID::toString)
                                        .orElse("unavailable"))
                        .addKeyValue("exceptionType", recoveryFailure.getClass().getName())
                        .setCause(recoveryFailure)
                        .log("Reservation cancellation DLT publication failed");
            }
        });
        return errorHandler;
    }

    @Bean
    @Profile("local")
    NewTopic reservationCancellationDeadLetterTopic(ReservationCancellationProperties properties) {
        return TopicBuilder.name(properties.deadLetterTopic())
                .partitions(3)
                .replicas(1)
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .config(TopicConfig.RETENTION_MS_CONFIG, "2592000000")
                .build();
    }

    private static void count(MeterRegistry metrics, String outcome) {
        metrics.counter(METRIC_NAME, "outcome", outcome).increment();
    }

    private static BackOff observedRetryBackOff(
            BackOff backOff,
            ConsumerRecord<?, ?> record,
            Exception exception,
            ReservationCancellationEventConverter converter,
            MeterRegistry metrics) {
        return () -> new BackOffExecution() {
            private final BackOffExecution delegate = backOff.start();
            private int deliveryAttempt;

            @Override
            public long nextBackOff() {
                long delay = delegate.nextBackOff();
                if (delay != STOP) {
                    deliveryAttempt++;
                    count(metrics, "retry");
                    LOGGER.atWarn()
                            .addKeyValue("outcome", "retry")
                            .addKeyValue("sourceTopic", record.topic())
                            .addKeyValue("partition", record.partition())
                            .addKeyValue("offset", record.offset())
                            .addKeyValue("deliveryAttempt", deliveryAttempt)
                            .addKeyValue("failureCode", "RETRYABLE_PROCESSING_FAILURE")
                            .addKeyValue(
                                    "eventId",
                                    eventId(exception, record.value(), converter)
                                            .map(UUID::toString)
                                            .orElse("unavailable"))
                            .log("Reservation cancellation retry scheduled");
                }
                return delay;
            }
        };
    }

    private static ReservationCancellationFailureCode failureCode(Throwable exception) {
        ReservationCancellationRejectedException rejection = findCause(
                        exception, ReservationCancellationRejectedException.class)
                .orElse(null);
        if (rejection != null) {
            return rejection.getFailureCode();
        }
        return ReservationCancellationFailureCode.PROCESSING_RETRIES_EXHAUSTED;
    }

    private static Optional<UUID> eventId(
            Throwable exception, Object rawValue, ReservationCancellationEventConverter converter) {
        ReservationCancellationRejectedException rejection = findCause(
                        exception, ReservationCancellationRejectedException.class)
                .orElse(null);
        if (rejection != null && rejection.getEventId().isPresent()) {
            return rejection.getEventId();
        }
        ReservationCancellationProcessingException processing = findCause(
                        exception, ReservationCancellationProcessingException.class)
                .orElse(null);
        if (processing != null) {
            return Optional.of(processing.getEventId());
        }
        return rawValue instanceof byte[] bytes ? converter.extractEventId(bytes) : Optional.empty();
    }

    private static <T extends Throwable> Optional<T> findCause(Throwable exception, Class<T> type) {
        Throwable current = exception;
        while (current != null) {
            if (type.isInstance(current)) {
                return Optional.of(type.cast(current));
            }
            current = current.getCause();
        }
        return Optional.empty();
    }

    private static final class BoundedHeadersDeadLetterRecoverer extends DeadLetterPublishingRecoverer {
        private final String deadLetterTopic;

        private BoundedHeadersDeadLetterRecoverer(
                KafkaTemplate<byte[], byte[]> template,
                String deadLetterTopic,
                String consumerGroup,
                ReservationCancellationEventConverter converter) {
            super(template, (record, exception) -> new TopicPartition(deadLetterTopic, record.partition()));
            this.deadLetterTopic = deadLetterTopic;
            setHeadersFunction((record, exception) ->
                    stableHeaders(record, failureCode(exception), exception, converter, consumerGroup));
        }

        @Override
        protected ProducerRecord<Object, Object> createProducerRecord(
                ConsumerRecord<?, ?> record,
                TopicPartition topicPartition,
                Headers ignored,
                byte[] deserializedKey,
                byte[] deserializedValue) {
            Object key = deserializedKey == null ? record.key() : deserializedKey;
            Object value = deserializedValue == null ? record.value() : deserializedValue;
            RecordHeaders headers = new RecordHeaders();
            for (String name : DLT_HEADERS) {
                Header header = ignored.lastHeader(name);
                if (header != null) {
                    headers.add(new RecordHeader(header.key(), header.value()));
                }
            }
            // The observed template injects the DLT producer context in the listener scope.
            // Copying the source context here would accumulate duplicate propagation headers.
            return new ProducerRecord<>(deadLetterTopic, topicPartition.partition(), key, value, headers);
        }
    }

    private static Headers stableHeaders(
            ConsumerRecord<?, ?> record,
            ReservationCancellationFailureCode failureCode,
            Throwable exception,
            ReservationCancellationEventConverter converter,
            String consumerGroup) {
        RecordHeaders headers = new RecordHeaders();
        add(headers, "rentflow.dlt.source-topic", record.topic());
        add(headers, "rentflow.dlt.source-partition", Integer.toString(record.partition()));
        add(headers, "rentflow.dlt.source-offset", Long.toString(record.offset()));
        add(headers, "rentflow.dlt.consumer-group", consumerGroup);
        add(headers, "rentflow.dlt.failure-code", failureCode.name());
        add(headers, "rentflow.dlt.failed-at", Instant.now().toString());
        eventId(exception, record.value(), converter)
                .ifPresent(id -> add(headers, "rentflow.dlt.event-id", id.toString()));
        return headers;
    }

    private static void add(Headers headers, String name, String value) {
        headers.add(new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8)));
    }
}
