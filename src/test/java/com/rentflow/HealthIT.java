package com.rentflow;

import java.io.IOException;
import java.net.ServerSocket;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.rentflow.model.InventoryItem;
import com.rentflow.model.InventoryStatus;
import com.rentflow.repository.InventoryRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(
        properties = {
            "spring.datasource.hikari.connection-timeout=1000",
            "spring.datasource.hikari.validation-timeout=500",
            "logging.level.org.springframework.boot.jdbc.health.DataSourceHealthIndicator=OFF"
        })
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HealthIT {

    private static final String INVENTORY_USERNAME = "inventory";
    private static final String INVENTORY_PASSWORD = "inventory-test";
    private static final Duration HEALTH_TRANSITION_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    @Container
    private static final PostgreSQLContainer POSTGRES = new FixedPortPostgreSQLContainer(
                    "postgres:18.4-alpine", availablePort())
            .withDatabaseName("rentflow")
            .withUsername("rentflow_admin")
            .withPassword("rentflow-admin-test")
            .withInitScript("testcontainers/init-inventory.sql");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InventoryRepository repository;

    @DynamicPropertySource
    static void registerDatasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> INVENTORY_USERNAME);
        registry.add("spring.datasource.password", () -> INVENTORY_PASSWORD);
    }

    @Test
    @Order(1)
    void exposesOnlyHealthWithHealthyDetailFreeProbes() throws Exception {
        assertHealthyProbe("/actuator/health/liveness");
        assertHealthyProbe("/livez");
        assertHealthyProbe("/actuator/health/readiness");
        assertHealthyProbe("/readyz");

        mockMvc.perform(get("/actuator"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._links.self").exists())
                .andExpect(jsonPath("$._links.health").exists())
                .andExpect(jsonPath("$._links['health-path']").exists())
                .andExpect(jsonPath("$._links.info").doesNotExist())
                .andExpect(jsonPath("$._links.metrics").doesNotExist())
                .andExpect(jsonPath("$._links.env").doesNotExist());
        mockMvc.perform(get("/actuator/info")).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
    }

    @Test
    @Order(2)
    void failedSchemaValidationPreventsAnAcceptingApplicationContext() {
        assertThatThrownBy(() -> {
                    try (var ignored = startApplication(Map.of(
                            "spring.flyway.default-schema", "missing_inventory",
                            "spring.flyway.schemas", "missing_inventory",
                            "spring.jpa.properties.hibernate.default_schema", "missing_inventory"))) {
                        // Startup must fail before the application can accept readiness traffic.
                    }
                })
                .hasStackTraceContaining("missing_inventory");
    }

    @Test
    @Order(3)
    void databaseLossChangesOnlyReadinessAndRecoversWithoutAnApplicationRestart() throws Exception {
        repository.saveAndFlush(
                new InventoryItem("DRILL-HEALTH", "Industrial drill", "Health probe item", InventoryStatus.AVAILABLE));

        stopDatabase();
        try {
            awaitStatus("/actuator/health/readiness", 503);
            awaitStatus("/readyz", 503);
            assertHealthyProbe("/actuator/health/liveness");
            assertHealthyProbe("/livez");

            mockMvc.perform(get("/api/v1/inventory/DRILL-HEALTH"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("urn:rentflow:problem:internal-error"))
                    .andExpect(jsonPath("$.title").value("Internal server error"))
                    .andExpect(jsonPath("$.status").value(500))
                    .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                    .andExpect(jsonPath("$.instance").value("/api/v1/inventory/DRILL-HEALTH"))
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.violations").doesNotExist());

            startDatabase();
            awaitStatus("/actuator/health/readiness", 200);
            awaitStatus("/readyz", 200);

            mockMvc.perform(get("/api/v1/inventory/DRILL-HEALTH"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.serialNumber").value("DRILL-HEALTH"))
                    .andExpect(jsonPath("$.name").value("Health probe item"));
        } finally {
            ensureDatabaseAvailable();
        }
    }

    private void assertHealthyProbe(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"status\":\"UP\"}"));
    }

    private void awaitStatus(String path, int expectedStatus) throws Exception {
        var deadline = System.nanoTime() + HEALTH_TRANSITION_TIMEOUT.toNanos();
        var actualStatus = -1;

        do {
            actualStatus = mockMvc.perform(get(path)).andReturn().getResponse().getStatus();
            if (actualStatus == expectedStatus) {
                return;
            }
            Thread.sleep(POLL_INTERVAL);
        } while (System.nanoTime() < deadline);

        assertThat(actualStatus).as("HTTP status for %s", path).isEqualTo(expectedStatus);
    }

    private void stopDatabase() {
        POSTGRES.getDockerClient()
                .stopContainerCmd(POSTGRES.getContainerId())
                .withTimeout(5)
                .exec();
        assertThat(POSTGRES.isRunning()).isFalse();
    }

    private void startDatabase() throws Exception {
        POSTGRES.getDockerClient().startContainerCmd(POSTGRES.getContainerId()).exec();
        awaitDatabaseAvailable();
    }

    private void ensureDatabaseAvailable() throws Exception {
        if (databaseAcceptsConnections()) {
            return;
        }
        if (POSTGRES.isRunning()) {
            POSTGRES.getDockerClient()
                    .restartContainerCmd(POSTGRES.getContainerId())
                    .withTimeout(5)
                    .exec();
        } else {
            POSTGRES.getDockerClient()
                    .startContainerCmd(POSTGRES.getContainerId())
                    .exec();
        }
        awaitDatabaseAvailable();
    }

    private void awaitDatabaseAvailable() throws Exception {
        var deadline = System.nanoTime() + HEALTH_TRANSITION_TIMEOUT.toNanos();
        do {
            if (databaseAcceptsConnections()) {
                return;
            }
            Thread.sleep(POLL_INTERVAL);
        } while (System.nanoTime() < deadline);

        assertThat(databaseAcceptsConnections())
                .as("PostgreSQL accepts connections after the container starts")
                .isTrue();
    }

    private boolean databaseAcceptsConnections() {
        if (!POSTGRES.isRunning()) {
            return false;
        }
        try (var connection =
                DriverManager.getConnection(POSTGRES.getJdbcUrl(), INVENTORY_USERNAME, INVENTORY_PASSWORD)) {
            return connection.isValid(1);
        } catch (SQLException exception) {
            return false;
        }
    }

    private AutoCloseable startApplication(Map<String, Object> overrides) {
        var properties = new java.util.HashMap<String, Object>();
        properties.put("spring.datasource.url", POSTGRES.getJdbcUrl());
        properties.put("spring.datasource.username", INVENTORY_USERNAME);
        properties.put("spring.datasource.password", INVENTORY_PASSWORD);
        properties.put("server.port", "0");
        properties.put("spring.main.banner-mode", "off");
        properties.put("logging.level.root", "OFF");
        properties.putAll(overrides);
        var arguments = properties.entrySet().stream()
                .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);

        return new SpringApplicationBuilder(InventoryApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(arguments);
    }

    private static int availablePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not reserve a host port for PostgreSQL", exception);
        }
    }

    private static final class FixedPortPostgreSQLContainer extends PostgreSQLContainer {

        private FixedPortPostgreSQLContainer(String imageName, int hostPort) {
            super(imageName);
            addFixedExposedPort(hostPort, PostgreSQLContainer.POSTGRESQL_PORT);
        }
    }
}
