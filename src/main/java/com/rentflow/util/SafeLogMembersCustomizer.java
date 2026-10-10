package com.rentflow.util;

import java.util.Set;

import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

import ch.qos.logback.classic.spi.ILoggingEvent;

public final class SafeLogMembersCustomizer implements StructuredLoggingJsonMembersCustomizer<ILoggingEvent> {
    private static final Set<String> MEMBERS = Set.of(
            "@timestamp",
            "log",
            "process",
            "service",
            "message",
            "error",
            "ecs",
            "traceId",
            "spanId",
            "eventId",
            "outcome",
            "sourceTopic",
            "partition",
            "offset",
            "failureCode",
            "deliveryAttempt",
            "exceptionType",
            "method",
            "route");

    @Override
    public void customize(JsonWriter.Members<ILoggingEvent> members) {
        members.applyingPathFilter(path -> {
            String name = path.toUnescapedString();
            return name.equals("error.message") || !MEMBERS.contains(name.split("\\.", 2)[0]);
        });
    }
}
