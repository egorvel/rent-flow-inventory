package com.rentflow.util;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

/** An allowlist at the export boundary also covers third-party observations. */
public final class SanitizingSpanExporter implements SpanExporter {
    private static final Set<String> SAFE_ATTRIBUTES = Set.of(
            "http.method",
            "http.request.method",
            "http.status_code",
            "http.response.status_code",
            "http.route",
            "method",
            "status",
            "uri",
            "outcome",
            "exception",
            "error",
            "exception.type",
            "exception.stacktrace",
            "jdbc.datasource.name",
            "jdbc.row-affected",
            "db.system",
            "db.system.name",
            "db.operation.name",
            "messaging.system",
            "messaging.operation",
            "messaging.operation.type",
            "messaging.operation.name",
            "messaging.destination.name",
            "messaging.destination.kind",
            "messaging.kafka.consumer.group",
            "messaging.consumer.group.name",
            "spring.kafka.listener.id",
            "spring.kafka.template.name",
            "listener.id",
            "bean.name",
            "eventId",
            "failureCode");
    private final SpanExporter delegate;

    public SanitizingSpanExporter(SpanExporter delegate) {
        this.delegate = delegate;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        return delegate.export(
                spans.stream().map(SanitizingSpanExporter::sanitize).toList());
    }

    public static SpanData sanitize(SpanData span) {
        return new DelegatingSpanData(span) {
            @Override
            public Attributes getAttributes() {
                return safeAttributes(super.getAttributes());
            }

            @Override
            public List<EventData> getEvents() {
                return super.getEvents().stream()
                        .filter(event -> event.getName().equals("exception"))
                        .map(event -> EventData.create(
                                event.getEpochNanos(), "exception", safeAttributes(event.getAttributes())))
                        .toList();
            }

            @Override
            public List<LinkData> getLinks() {
                return super.getLinks().stream()
                        .map(link -> LinkData.create(link.getSpanContext()))
                        .toList();
            }

            @Override
            public StatusData getStatus() {
                return StatusData.create(super.getStatus().getStatusCode(), "");
            }

            @Override
            public int getTotalAttributeCount() {
                return getAttributes().size();
            }
        };
    }

    private static Attributes safeAttributes(Attributes source) {
        AttributesBuilder safe = Attributes.builder();
        source.forEach((key, value) -> {
            if (SAFE_ATTRIBUTES.contains(key.getKey())) {
                if (key.getKey().equals("exception.stacktrace")) {
                    safe.put("exception.stacktrace", SafeStackTracePrinter.sanitizeExportedStack(value.toString()));
                } else if (Set.of("exception", "error").contains(key.getKey())) {
                    if (value.toString().matches("[\\w.$]+")) {
                        put(safe, key, value);
                    }
                } else if (key.getKey().equals("uri") || key.getKey().equals("http.route")) {
                    String route = value.toString();
                    if (route.contains("{")
                            || Set.of("/api/v1/inventory", "/api/v1/inventory-history", "UNKNOWN", "NOT_FOUND")
                                    .contains(route)) {
                        put(safe, key, value);
                    }
                } else {
                    put(safe, key, value);
                }
            }
        });
        return safe.build();
    }

    @SuppressWarnings("unchecked")
    private static <T> void put(AttributesBuilder target, AttributeKey<T> key, Object value) {
        target.put(key, (T) value);
    }

    @Override
    public CompletableResultCode flush() {
        return delegate.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
        return delegate.shutdown();
    }
}
