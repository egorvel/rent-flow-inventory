package com.rentflow.util;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class SafeLoggingTest {
    @Test
    void encodesEcsIdentityCorrelationAndSafeExceptionsWithoutUnapprovedFields() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.application.name", "rent-flow-inventory")
                .withProperty("logging.structured.ecs.service.environment", "local")
                .withProperty("logging.structured.ecs.service.node-name", "test-instance")
                .withProperty("logging.structured.json.customizer", SafeLogMembersCustomizer.class.getName())
                .withProperty("logging.structured.json.stacktrace.printer", SafeStackTracePrinter.class.getName());
        LoggerContext context = new LoggerContext();
        try {
            context.putObject(Environment.class.getName(), environment);
            StructuredLogEncoder encoder = new StructuredLogEncoder();
            encoder.setContext(context);
            encoder.setFormat("ecs");
            encoder.start();
            String secret = "synthetic-private-value";
            LoggingEvent event = new LoggingEvent();
            event.setLoggerName("com.rentflow.test");
            event.setLevel(Level.ERROR);
            event.setMessage("Safe failure diagnostic");
            event.setTimeStamp(System.currentTimeMillis());
            event.setThreadName("test");
            event.setThrowableProxy(
                    new ThrowableProxy(new IllegalStateException(secret, new RuntimeException(secret))));
            event.setMDCPropertyMap(Map.of(
                    "traceId", "11111111111111111111111111111111", "spanId", "2222222222222222", "customer", secret));
            event.setKeyValuePairs(List.of(new KeyValuePair("eventId", "event"), new KeyValuePair("payload", secret)));
            String json = new String(encoder.encode(event), StandardCharsets.UTF_8);
            JsonNode data = JsonMapper.builder().build().readTree(json);
            assertThat(json).doesNotContain(secret, "payload", "customer");
            assertThat(data.path("service").path("name").asText()).isEqualTo("rent-flow-inventory");
            assertThat(data.path("service").path("environment").asText()).isEqualTo("local");
            assertThat(data.path("traceId").asText()).isEqualTo("11111111111111111111111111111111");
            assertThat(data.path("eventId").asText()).isEqualTo("event");
            assertThat(data.path("error").path("type").asText()).isEqualTo(IllegalStateException.class.getName());
            assertThat(data.path("error").path("message").isMissingNode()).isTrue();
            assertThat(data.path("error").path("stack_trace").asText()).contains("SafeLoggingTest", "RuntimeException");
            encoder.stop();
        } finally {
            context.stop();
        }
    }
}
