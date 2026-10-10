package com.rentflow.util;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaTraceHeadersTest {
    @Test
    void normalizesOneValidContextAndRemovesBaggageAndAlternativePropagation() {
        RecordHeaders headers = new RecordHeaders();
        String parent = "00-11111111111111111111111111111111-2222222222222222-01";
        headers.add("traceparent", "invalid".getBytes(StandardCharsets.UTF_8));
        headers.add("traceparent", parent.getBytes(StandardCharsets.UTF_8));
        headers.add("tracestate", "vendor=state".getBytes(StandardCharsets.UTF_8));
        headers.add("baggage", "customer=synthetic-private-value".getBytes(StandardCharsets.UTF_8));
        headers.add("b3", "bad".getBytes(StandardCharsets.UTF_8));
        KafkaTraceHeaders.normalize(headers);
        assertThat(headers.headers("traceparent")).hasSize(1);
        assertThat(new String(headers.lastHeader("traceparent").value(), StandardCharsets.UTF_8))
                .isEqualTo(parent);
        assertThat(new String(headers.lastHeader("tracestate").value(), StandardCharsets.UTF_8))
                .isEqualTo("vendor=state");
        assertThat(headers.lastHeader("baggage")).isNull();
        assertThat(headers.lastHeader("b3")).isNull();
    }

    @Test
    void malformedAndOversizedContextsAreIgnored() {
        for (String value :
                new String[] {"malformed", "0".repeat(513), "00-00000000000000000000000000000000-2222222222222222-01"
                }) {
            RecordHeaders headers = new RecordHeaders();
            headers.add("traceparent", value.getBytes(StandardCharsets.UTF_8));
            headers.add("tracestate", "vendor=state".getBytes(StandardCharsets.UTF_8));
            KafkaTraceHeaders.normalize(headers);
            assertThat(headers.toArray()).isEmpty();
        }
        KafkaTraceHeaders.normalize(new RecordHeaders());
    }
}
