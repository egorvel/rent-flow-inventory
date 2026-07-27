package com.rentflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rentflow.model.InventoryItem;
import com.rentflow.model.InventoryStatus;
import com.rentflow.repository.InventoryRepository;
import com.rentflow.support.PostgresIntegrationTest;
import java.sql.DriverManager;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MigrationIT extends PostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private InventoryRepository repository;

    @Autowired
    private Flyway flyway;

    @Test
    void runsAsRestrictedInventoryRoleInRentflowDatabase() {
        assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class))
                .isEqualTo(INVENTORY_USERNAME);
        assertThat(jdbcTemplate.queryForObject("SELECT current_database()", String.class))
                .isEqualTo("rentflow");
    }

    @Test
    void createsOnlyInventoryObjectsAndRecordsMigrationOnce() throws Exception {
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT count(*)
                        FROM information_schema.tables
                        WHERE table_schema = 'inventory'
                          AND table_name = 'inventory_items'
                        """, Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM inventory.flyway_schema_history WHERE version = '1'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("""
                        SELECT indexname
                        FROM pg_indexes
                        WHERE schemaname = 'inventory'
                          AND tablename = 'inventory_items'
                        """, String.class))
                .contains("inventory_items_pkey", "idx_inventory_items_status", "idx_inventory_items_type_lower");
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT count(*)
                        FROM information_schema.tables
                        WHERE table_schema = 'public'
                          AND table_name IN ('inventory_items', 'flyway_schema_history')
                        """, Integer.class)).isZero();

        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.prepareStatement("SELECT marker FROM rental.sentinel WHERE id = 1")) {
            try (var resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString("marker")).isEqualTo("untouched");
            }
        }

        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM inventory.flyway_schema_history WHERE version = '1'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void databaseConstraintsRejectInvalidRows() {
        assertInvalidRow("/INVALID", "Drill", "Name", "AVAILABLE");
        assertInvalidRow("DRILL-001", " Drill", "Name", "AVAILABLE");
        assertInvalidRow("DRILL-001", "Drill", "Name ", "AVAILABLE");
        assertInvalidRow("DRILL-001", "Drill", "Name", "UNKNOWN");
        assertInvalidRow("S".repeat(65), "Drill", "Name", "AVAILABLE");
        assertInvalidRow("DRILL-001", "T".repeat(101), "Name", "AVAILABLE");
        assertInvalidRow("DRILL-001", "Drill", "N".repeat(201), "AVAILABLE");
    }

    @Test
    void committedRowsSurviveASecondApplicationContext() {
        repository.saveAndFlush(new InventoryItem("DRILL-RESTART", "Drill", "Restart", InventoryStatus.AVAILABLE));

        try (var secondContext = startApplication(Map.of())) {
            var secondRepository = secondContext.getBean(InventoryRepository.class);
            assertThat(secondRepository.findById("DRILL-RESTART")).isPresent();
        }
    }

    @Test
    void startupFailsWhenTheOwnedSchemaIsMissing() {
        assertThatThrownBy(() -> {
                    try (var ignored = startApplication(Map.of(
                            "spring.flyway.default-schema", "missing_inventory",
                            "spring.flyway.schemas", "missing_inventory",
                            "spring.jpa.properties.hibernate.default_schema", "missing_inventory"))) {
                        // Startup must fail before a context can be used.
                    }
                })
                .hasStackTraceContaining("missing_inventory");
    }

    private void assertInvalidRow(String serialNumber, String type, String name, String status) {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO inventory.inventory_items (serial_number, type, name, status)
                        VALUES (?, ?, ?, ?)
                        """, serialNumber, type, name, status))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private ConfigurableApplicationContext startApplication(Map<String, Object> overrides) {
        var properties = new java.util.HashMap<String, Object>();
        properties.put("spring.datasource.url", POSTGRES.getJdbcUrl());
        properties.put("spring.datasource.username", INVENTORY_USERNAME);
        properties.put("spring.datasource.password", INVENTORY_PASSWORD);
        properties.put("spring.main.banner-mode", "off");
        properties.put("logging.level.root", "OFF");
        properties.putAll(overrides);
        var arguments = properties.entrySet().stream()
                .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);

        return new SpringApplicationBuilder(InventoryApplication.class)
                .web(WebApplicationType.NONE)
                .run(arguments);
    }
}
