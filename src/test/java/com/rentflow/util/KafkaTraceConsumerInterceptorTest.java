package com.rentflow.util;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaTraceConsumerInterceptorTest {
    @Test
    void normalizesEveryRecordWithoutReplacingBytesOffsetsOrBusinessHeaders() {
        byte[] key = "key".getBytes(StandardCharsets.UTF_8);
        byte[] value = "value".getBytes(StandardCharsets.UTF_8);
        ConsumerRecord<Object, Object> valid = new ConsumerRecord<>("source", 0, 7L, key, value);
        String parent = "00-11111111111111111111111111111111-2222222222222222-01";
        valid.headers().add("traceparent", "invalid".getBytes(StandardCharsets.UTF_8));
        valid.headers().add("traceparent", parent.getBytes(StandardCharsets.UTF_8));
        valid.headers().add("business", value);
        ConsumerRecord<Object, Object> invalid = new ConsumerRecord<>("source", 0, 8L, key, value);
        invalid.headers().add("traceparent", "invalid".getBytes(StandardCharsets.UTF_8));
        invalid.headers().add("baggage", value);
        TopicPartition partition = new TopicPartition("source", 0);
        ConsumerRecords<Object, Object> records = new ConsumerRecords<>(
                Map.of(partition, List.of(valid, invalid)), Map.of(partition, new OffsetAndMetadata(9L)));
        try (KafkaTraceConsumerInterceptor interceptor = new KafkaTraceConsumerInterceptor()) {
            assertThat(interceptor.onConsume(records)).isSameAs(records);
        }
        assertThat(valid.headers().headers("traceparent")).hasSize(1);
        assertThat(valid.headers().lastHeader("traceparent").value())
                .isEqualTo(parent.getBytes(StandardCharsets.UTF_8));
        assertThat(valid.headers().lastHeader("business").value()).isSameAs(value);
        assertThat(invalid.headers().toArray()).isEmpty();
        assertThat(records.nextOffsets()).containsEntry(partition, new OffsetAndMetadata(9L));
        assertThat(records.records(partition)).containsExactly(valid, invalid);
        for (ConsumerRecord<Object, Object> record : records) {
            assertThat(record.key()).isSameAs(key);
            assertThat(record.value()).isSameAs(value);
        }
    }
}
