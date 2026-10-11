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

class StructuredLoggingTest {
    @Test
    void retainsEcsIdentityCorrelationDiagnosticFieldsAndExceptionCauses() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.application.name", "rent-flow-inventory")
                .withProperty("logging.structured.ecs.service.environment", "local")
                .withProperty("logging.structured.ecs.service.node-name", "test-instance")
                .withProperty("logging.structured.json.stacktrace.max-length", "16384")
                .withProperty("logging.structured.json.stacktrace.max-throwable-depth", "64");
        LoggerContext context = new LoggerContext();
        try {
            context.putObject(Environment.class.getName(), environment);
            StructuredLogEncoder encoder = new StructuredLogEncoder();
            encoder.setContext(context);
            encoder.setFormat("ecs");
            encoder.start();
            IllegalStateException failure = new IllegalStateException(
                    "Equipment DRILL-001 could not be released", new RuntimeException("Database connection timed out"));
            failure.addSuppressed(new IllegalArgumentException("Cleanup also failed"));
            LoggingEvent event = new LoggingEvent();
            event.setLoggerName("com.rentflow.test");
            event.setLevel(Level.ERROR);
            event.setMessage("Equipment processing failed");
            event.setTimeStamp(System.currentTimeMillis());
            event.setThreadName("test");
            event.setThrowableProxy(new ThrowableProxy(failure));
            event.setMDCPropertyMap(Map.of(
                    "traceId",
                    "11111111111111111111111111111111",
                    "spanId",
                    "2222222222222222",
                    "diagnostic",
                    "retry"));
            event.setKeyValuePairs(
                    List.of(new KeyValuePair("eventId", "event"), new KeyValuePair("serialNumber", "DRILL-001")));
            String json = new String(encoder.encode(event), StandardCharsets.UTF_8);
            JsonNode data = JsonMapper.builder().build().readTree(json);
            assertThat(data.path("service").path("name").asText()).isEqualTo("rent-flow-inventory");
            assertThat(data.path("service").path("environment").asText()).isEqualTo("local");
            assertThat(data.path("traceId").asText()).isEqualTo("11111111111111111111111111111111");
            assertThat(data.path("eventId").asText()).isEqualTo("event");
            assertThat(data.path("serialNumber").asText()).isEqualTo("DRILL-001");
            assertThat(data.path("diagnostic").asText()).isEqualTo("retry");
            assertThat(data.path("error").path("type").asText()).isEqualTo(IllegalStateException.class.getName());
            assertThat(data.path("error").path("message").asText())
                    .isEqualTo("Equipment DRILL-001 could not be released");
            assertThat(data.path("error").path("stack_trace").asText())
                    .contains(
                            "StructuredLoggingTest",
                            "RuntimeException",
                            "Database connection timed out",
                            "Cleanup also failed");
            assertThat(data.path("error").path("stack_trace").asText().length()).isLessThanOrEqualTo(16384);
            encoder.stop();
        } finally {
            context.stop();
        }
    }
}
