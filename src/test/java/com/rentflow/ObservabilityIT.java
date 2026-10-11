package com.rentflow;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import com.rentflow.model.InventoryItem;
import com.rentflow.model.InventoryStatus;
import com.rentflow.repository.InventoryRepository;
import com.rentflow.support.PostgresIntegrationTest;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=0", "spring.kafka.listener.auto-startup=false",
            "management.tracing.sampling.probability=1.0", "management.tracing.export.otlp.enabled=false"
        })
@AutoConfigureTracing
@AutoConfigureMetrics
@Import(ObservabilityIT.RecordingTraces.class)
class ObservabilityIT extends PostgresIntegrationTest {
    private static final InMemorySpanExporter SPANS = InMemorySpanExporter.create();

    @Autowired
    private Environment environment;

    @Autowired
    private InventoryRepository inventory;

    @Autowired
    private MeterRegistry metrics;

    @Autowired
    private SdkTracerProvider tracerProvider;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private javax.sql.DataSource datasource;

    @Autowired
    private com.rentflow.service.IdempotencyCleanupService cleanup;

    @BeforeEach
    void resetSpans() {
        tracerProvider.forceFlush().join(5, java.util.concurrent.TimeUnit.SECONDS);
        SPANS.reset();
    }

    @Test
    void isolatesManagementAndKeepsApplicationProbesAndHttpHistograms() throws Exception {
        assertThat(get("local.server.port", "/actuator/prometheus", null).statusCode())
                .isEqualTo(404);
        HttpResponse<String> scrape = get("local.management.port", "/actuator/prometheus", null);
        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.body())
                .contains(
                        "jvm_memory_used_bytes",
                        "hikaricp_connections",
                        "service=\"rent-flow-inventory\"",
                        "environment=\"local\"");
        assertThat(get("local.management.port", "/actuator/env", null).statusCode())
                .isEqualTo(404);
        assertThat(get("local.server.port", "/livez", null).statusCode()).isEqualTo(200);
        HttpResponse<String> readiness = get("local.server.port", "/readyz", null);
        assertThat(readiness.statusCode()).isEqualTo(200);
        assertThat(readiness.body()).isEqualTo("{\"status\":\"UP\"}");
        get("local.server.port", "/api/v1/inventory", null);
        assertThat(get("local.management.port", "/actuator/prometheus", null).body())
                .contains("http_server_requests_seconds_bucket");
    }

    @Test
    void retainsHttpPathAndSqlWhileExcludingHeadersQueryStringsAndParameterValues() throws Exception {
        String serial = "TRACE-DIAGNOSTIC-SERIAL";
        inventory.saveAndFlush(
                new InventoryItem(serial, "Drill", "synthetic-private-value", InventoryStatus.AVAILABLE));
        tracerProvider.forceFlush().join(5, java.util.concurrent.TimeUnit.SECONDS);
        SPANS.reset();
        String traceId = "11111111111111111111111111111111";
        HttpResponse<String> response = get(
                "local.server.port",
                "/api/v1/inventory/" + serial + "?private=synthetic-private-value",
                "00-" + traceId + "-2222222222222222-01");
        assertThat(response.statusCode()).isEqualTo(200);
        tracerProvider.forceFlush().join(5, java.util.concurrent.TimeUnit.SECONDS);
        List<SpanData> spans = SPANS.getFinishedSpanItems().stream()
                .filter(span -> span.getTraceId().equals(traceId))
                .toList();
        assertThat(spans).anySatisfy(span -> {
            assertThat(span.getKind()).isEqualTo(io.opentelemetry.api.trace.SpanKind.SERVER);
            assertThat(span.getParentSpanId()).isEqualTo("2222222222222222");
            assertThat(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("http.url")))
                    .isEqualTo("/api/v1/inventory/" + serial);
        });
        assertThat(spans).anySatisfy(span -> assertThat(span.getName()).contains("connection"));
        assertThat(spans).anySatisfy(span -> {
            assertThat(span.getName()).contains("query");
            assertThat(span.getAttributes().toString()).contains("jdbc.query", "inventory_item", "?");
        });
        assertThat(spans).allSatisfy(span -> {
            assertThat(span.getAttributes().toString() + span.getEvents())
                    .doesNotContain(
                            "synthetic-private-value",
                            "jdbc.params",
                            "synthetic-header-value",
                            "synthetic-cookie-value",
                            "synthetic-baggage-value");
            assertThat(span.getResource()
                            .getAttribute(io.opentelemetry.api.common.AttributeKey.stringKey("service.name")))
                    .isEqualTo("rent-flow-inventory");
        });
        assertThat(metrics.get("http.server.requests")
                        .tag("uri", "/api/v1/inventory/{serialNumber}")
                        .timer()
                        .count())
                .isPositive();
        assertThat(get("local.management.port", "/actuator/prometheus", null).body())
                .doesNotContain(serial);
        assertThat(environment.getProperty("jdbc.datasource-proxy.include-parameter-values", Boolean.class))
                .isFalse();
        assertThat(environment.getProperty("management.tracing.baggage.enabled", Boolean.class))
                .isFalse();
    }

    @Test
    void failingSqlRetainsStatementExceptionMessageAndStack() {
        assertThatThrownBy(() -> jdbc.queryForObject("SELECT 'not-an-integer'::integer", Integer.class))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        tracerProvider.forceFlush().join(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(SPANS.getFinishedSpanItems()).anySatisfy(span -> {
            assertThat(span.getStatus().getStatusCode()).isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
            assertThat(span.getAttributes().toString()).contains("SELECT 'not-an-integer'::integer");
            assertThat(span.getEvents().toString())
                    .contains("exception.type", "exception.message", "exception.stacktrace", "not-an-integer");
        });
    }

    @Test
    void doesNotCaptureHttpBodiesOrBoundSqlParameters() throws Exception {
        int port = environment.getRequiredProperty("local.server.port", Integer.class);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/inventory"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer synthetic-header-value")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"serialNumber":"TRACE-BODY-ITEM","type":"Drill",
                         "name":"synthetic-body-value","status":"AVAILABLE"}
                        """))
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertThat(client.send(request, HttpResponse.BodyHandlers.ofString())
                            .statusCode())
                    .isEqualTo(201);
        }
        assertThat(jdbc.queryForObject("SELECT ?::text", String.class, "synthetic-bound-value"))
                .isEqualTo("synthetic-bound-value");
        tracerProvider.forceFlush().join(5, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(SPANS.getFinishedSpanItems())
                .isNotEmpty()
                .allSatisfy(span -> assertThat(span.getAttributes().toString() + span.getEvents())
                        .doesNotContain(
                                "synthetic-body-value",
                                "synthetic-bound-value",
                                "synthetic-header-value",
                                "jdbc.params"));
        assertThat(SPANS.getFinishedSpanItems())
                .anySatisfy(span -> assertThat(span.getAttributes().toString()).contains("SELECT ?::text"));
    }

    @Test
    void retainsFrameworkEventsLinkAttributesAndErrorDescriptions() {
        io.opentelemetry.api.trace.Tracer tracer = tracerProvider.get("inventory-diagnostics-test");
        io.opentelemetry.api.trace.Span previous =
                tracer.spanBuilder("previous.work").startSpan();
        previous.end();
        io.opentelemetry.api.trace.Span span = tracer.spanBuilder("diagnostic.work")
                .addLink(
                        previous.getSpanContext(),
                        io.opentelemetry.api.common.Attributes.of(
                                io.opentelemetry.api.common.AttributeKey.stringKey("diagnostic.cause"), "retry"))
                .startSpan();
        span.setAttribute("diagnostic.phase", "recovery");
        span.addEvent("recovery.scheduled");
        span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, "Broker acknowledgement timed out");
        span.end();
        tracerProvider.forceFlush().join(5, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(SPANS.getFinishedSpanItems()).anySatisfy(data -> {
            assertThat(data.getSpanId()).isEqualTo(span.getSpanContext().getSpanId());
            assertThat(data.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("diagnostic.phase")))
                    .isEqualTo("recovery");
            assertThat(data.getEvents())
                    .singleElement()
                    .satisfies(event -> assertThat(event.getName()).isEqualTo("recovery.scheduled"));
            assertThat(data.getLinks()).singleElement().satisfies(link -> {
                assertThat(link.getSpanContext()).isEqualTo(previous.getSpanContext());
                assertThat(link.getAttributes()
                                .get(io.opentelemetry.api.common.AttributeKey.stringKey("diagnostic.cause")))
                        .isEqualTo("retry");
            });
            assertThat(data.getStatus().getDescription()).isEqualTo("Broker acknowledgement timed out");
        });
    }

    @Test
    void backgroundCountTimesOutWhenTheTableIsLocked() throws Exception {
        try (java.sql.Connection connection = datasource.getConnection();
                java.util.concurrent.ExecutorService worker =
                        java.util.concurrent.Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (java.sql.Statement statement = connection.createStatement()) {
                statement.execute("LOCK TABLE inventory.inventory_status_transition_requests IN ACCESS EXCLUSIVE MODE");
            }
            java.util.concurrent.Future<Long> count = worker.submit(cleanup::countExpired);
            try {
                assertThatThrownBy(() -> count.get(8, java.util.concurrent.TimeUnit.SECONDS))
                        .isInstanceOf(java.util.concurrent.ExecutionException.class)
                        .hasCauseInstanceOf(org.springframework.dao.QueryTimeoutException.class);
            } finally {
                connection.rollback();
            }
        }
    }

    private HttpResponse<String> get(String portProperty, String path, String parent) throws Exception {
        int port = environment.getRequiredProperty(portProperty, Integer.class);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        request.header("Authorization", "Bearer synthetic-header-value");
        request.header("Cookie", "session=synthetic-cookie-value");
        request.header("baggage", "customer=synthetic-baggage-value");
        if (parent != null) {
            request.header("traceparent", parent);
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingTraces {
        @Bean
        SpanExporter recordingSpanExporter() {
            return SPANS;
        }
    }
}
