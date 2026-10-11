package com.rentflow;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.rentflow.model.InventoryItem;
import com.rentflow.model.InventoryStatus;
import com.rentflow.repository.InventoryRepository;
import com.rentflow.support.PostgresIntegrationTest;
import com.sun.net.httpserver.HttpServer;

import io.micrometer.tracing.Tracer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=0",
            "spring.kafka.listener.auto-startup=false",
            "management.tracing.sampling.probability=1",
            "management.tracing.export.otlp.enabled=true",
            "management.opentelemetry.tracing.export.max-queue-size=1",
            "management.opentelemetry.tracing.export.max-batch-size=1",
            "management.opentelemetry.tracing.export.schedule-delay=10ms",
            "management.opentelemetry.tracing.export.otlp.connect-timeout=200ms",
            "management.opentelemetry.tracing.export.otlp.timeout=200ms"
        })
@AutoConfigureTracing(export = true)
class ObservabilityExporterUnavailableIT extends PostgresIntegrationTest {
    private static final CountDownLatch RECEIVED = new CountDownLatch(1);
    private static final CountDownLatch FINISH = new CountDownLatch(1);
    private static final AtomicReference<String> PAYLOAD = new AtomicReference<>();
    private static final ExecutorService WORKERS = Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpServer COLLECTOR = startCollector();

    @Autowired
    private Environment environment;

    @Autowired
    private InventoryRepository inventory;

    @Autowired
    private Tracer tracer;

    @DynamicPropertySource
    static void collectorProperties(DynamicPropertyRegistry properties) {
        properties.add(
                "management.opentelemetry.tracing.export.otlp.endpoint",
                () -> "http://127.0.0.1:" + COLLECTOR.getAddress().getPort() + "/v1/traces");
    }

    private static HttpServer startCollector() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(WORKERS);
            server.createContext("/v1/traces", exchange -> {
                PAYLOAD.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
                RECEIVED.countDown();
                try {
                    FINISH.await(5, TimeUnit.SECONDS);
                    exchange.sendResponseHeaders(503, -1);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to start test collector", exception);
        }
    }

    @AfterAll
    static void stopCollector() {
        FINISH.countDown();
        COLLECTOR.stop(0);
        WORKERS.close();
    }

    @Test
    void slowUnavailableAndSaturatedCollectorDoesNotBlockRequestsOrReadiness() throws Exception {
        String serial = "EXPORTER-OUTAGE";
        inventory.saveAndFlush(
                new InventoryItem(serial, "Drill", "synthetic-private-value", InventoryStatus.AVAILABLE));
        assertThat(RECEIVED.await(5, TimeUnit.SECONDS)).isTrue();
        for (int index = 0; index < 1000; index++) {
            io.micrometer.tracing.Span span =
                    tracer.nextSpan().name("queue-pressure").start();
            span.tag("jdbc.query[0]", "synthetic-private-value");
            span.error(new IllegalStateException("synthetic-private-value"));
            span.end();
        }
        int port = environment.getRequiredProperty("local.server.port", Integer.class);
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertThat(request(client, port, "/api/v1/inventory/" + serial).statusCode())
                    .isEqualTo(200);
            assertThat(request(client, port, "/readyz").statusCode()).isEqualTo(200);
            assertThat(PAYLOAD.get()).contains("service.name", "rent-flow-inventory");
            FINISH.countDown();
            COLLECTOR.stop(0);
            assertThat(request(client, port, "/api/v1/inventory/" + serial).statusCode())
                    .isEqualTo(200);
            assertThat(request(client, port, "/readyz").statusCode()).isEqualTo(200);
        }
    }

    private HttpResponse<String> request(HttpClient client, int port, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(3))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
