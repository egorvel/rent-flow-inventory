package com.rentflow.util;

import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerInterceptor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

/** Normalizes propagation before poll returns and Spring starts listener observations. */
public final class KafkaTraceConsumerInterceptor implements ConsumerInterceptor<Object, Object> {
    @Override
    public ConsumerRecords<Object, Object> onConsume(ConsumerRecords<Object, Object> records) {
        for (ConsumerRecord<Object, Object> record : records) {
            KafkaTraceHeaders.normalize(record.headers());
        }
        return records;
    }

    @Override
    public void onCommit(Map<TopicPartition, OffsetAndMetadata> offsets) {}

    @Override
    public void configure(Map<String, ?> configs) {}

    @Override
    public void close() {}
}
