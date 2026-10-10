package com.rentflow.util;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;

public final class KafkaTraceHeaders {
    private KafkaTraceHeaders() {}

    public static void normalize(Headers headers) {
        W3CTraceContextPropagator propagator = W3CTraceContextPropagator.getInstance();
        Context context = propagator.extract(Context.root(), headers, new TextMapGetter<Headers>() {
            @Override
            public Iterable<String> keys(Headers carrier) {
                return List.of("traceparent", "tracestate");
            }

            @Override
            public String get(Headers carrier, String key) {
                Header header = carrier.lastHeader(key);
                return header == null || header.value() == null || header.value().length > 512
                        ? null
                        : new String(header.value(), StandardCharsets.UTF_8);
            }
        });
        for (String name : List.of(
                "traceparent",
                "tracestate",
                "baggage",
                "b3",
                "X-B3-TraceId",
                "X-B3-SpanId",
                "X-B3-Sampled",
                "X-B3-Flags")) {
            headers.remove(name);
        }
        if (Span.fromContext(context).getSpanContext().isValid()) {
            propagator.inject(
                    context,
                    headers,
                    (carrier, key, value) -> carrier.add(key, value.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
