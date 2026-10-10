package com.rentflow.util;

import org.junit.jupiter.api.Test;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import static org.assertj.core.api.Assertions.assertThat;

class SanitizingSpanExporterTest {
    @Test
    void stripsValuesAtExportWhileRetainingTimingCorrelationAndSafeFrames() {
        InMemorySpanExporter sink = InMemorySpanExporter.create();
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(new SanitizingSpanExporter(sink)))
                .build()) {
            OpenTelemetrySdk sdk =
                    OpenTelemetrySdk.builder().setTracerProvider(provider).build();
            Span span = sdk.getTracer("test").spanBuilder("query").startSpan();
            String secret = "synthetic-private-value";
            span.setAttribute("jdbc.query[0]", "SELECT '" + secret + "'");
            span.setAttribute("jdbc.params[0]", secret);
            span.setAttribute("url.full", "http://example/item/" + secret);
            span.setAttribute("jdbc.datasource.name", "dataSource");
            span.setAttribute("http.route", "/api/v1/inventory/{serialNumber}");
            span.recordException(new IllegalStateException(secret, new RuntimeException(secret)));
            span.setStatus(StatusCode.ERROR, secret);
            span.addEvent(secret, Attributes.empty());
            span.end();
            assertThat(sink.getFinishedSpanItems()).singleElement().satisfies(data -> {
                assertThat(data.getTraceId()).isEqualTo(span.getSpanContext().getTraceId());
                assertThat(data.getEndEpochNanos()).isGreaterThan(data.getStartEpochNanos());
                assertThat(data.getAttributes().toString())
                        .contains("dataSource", "{serialNumber}")
                        .doesNotContain(secret, "jdbc.query", "jdbc.params", "url.full");
                assertThat(data.getStatus().getDescription()).isEmpty();
                assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
                assertThat(data.getEvents())
                        .singleElement()
                        .satisfies(event -> assertThat(event.getAttributes().toString())
                                .contains("IllegalStateException", "SanitizingSpanExporterTest")
                                .doesNotContain(secret, "exception.message"));
            });
        }
    }
}
