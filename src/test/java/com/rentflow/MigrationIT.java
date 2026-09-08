package com.rentflow;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.rentflow.model.InventoryItem;
import com.rentflow.model.InventoryStatus;
import com.rentflow.model.InventoryStatusHistory;
import com.rentflow.repository.InventoryRepository;
import com.rentflow.repository.InventoryStatusHistoryRepository;
import com.rentflow.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MigrationIT extends PostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private InventoryRepository repository;

    @Autowired
    private InventoryStatusHistoryRepository historyRepository;

    @Autowired
    private Flyway flyway;

    @BeforeEach
    void clearOwnedTables() {
        historyRepository.deleteAllInBatch();
        repository.deleteAllInBatch();
    }

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
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM inventory.flyway_schema_history WHERE version = '2'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("""
                        SELECT indexname
                        FROM pg_indexes
                        WHERE schemaname = 'inventory'
                          AND tablename = 'inventory_items'
                        """, String.class))
                .contains("inventory_items_pkey", "idx_inventory_items_status", "idx_inventory_items_type_lower");
        assertThat(jdbcTemplate.queryForList("""
                        SELECT indexname
                        FROM pg_indexes
                        WHERE schemaname = 'inventory'
                          AND tablename = 'inventory_status_history'
                        """, String.class))
                .contains("inventory_status_history_pkey", "idx_inventory_status_history_transitioned_at_id");
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT count(*)
                        FROM information_schema.tables
                        WHERE table_schema = 'public'
                          AND table_name IN (
                              'inventory_items',
                              'inventory_status_history',
                              'flyway_schema_history'
                          )
                        """, Integer.class)).isZero();

        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                PreparedStatement statement =
                        connection.prepareStatement("SELECT marker FROM rental.sentinel WHERE id = 1")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString("marker")).isEqualTo("untouched");
            }
        }

        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM inventory.flyway_schema_history WHERE version = '1'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM inventory.flyway_schema_history WHERE version = '2'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void createsOwnedIdempotencyLedgerWithExpiryIndexAndNoItemForeignKey() {
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM inventory.flyway_schema_history WHERE version = '3' AND success",
                        Integer.class))
                .isOne();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT tableowner FROM pg_tables WHERE schemaname = 'inventory' AND tablename = 'inventory_status_transition_requests'",
                        String.class))
                .isEqualTo(INVENTORY_USERNAME);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'inventory_status_transition_requests'",
                        Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT indexdef FROM pg_indexes WHERE schemaname = 'inventory' AND indexname = 'idx_inventory_status_transition_requests_expiry'",
                        String.class))
                .contains("expires_at, idempotency_key");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM information_schema.table_constraints WHERE table_schema = 'inventory' AND table_name = 'inventory_status_transition_requests' AND constraint_type = 'FOREIGN KEY'",
                        Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForList(
                        "SELECT column_name || ':' || data_type FROM information_schema.columns WHERE table_schema = 'inventory' AND table_name = 'inventory_status_transition_requests'",
                        String.class))
                .containsExactlyInAnyOrder(
                        "idempotency_key:uuid",
                        "fingerprint:character varying",
                        "http_status:integer",
                        "outcome:jsonb",
                        "recorded_at:timestamp with time zone",
                        "expires_at:timestamp with time zone");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void idempotencyConstraintsRejectInvalidTerminalOutcomes() {
        for (String invalid : java.util.List.of(
                "'not-a-hash', 204, '{\"status\":204}'::jsonb, INTERVAL '7 days'",
                "repeat('a',64), 500, '{\"status\":500}'::jsonb, INTERVAL '7 days'",
                "repeat('a',64), 204, '{\"status\":404}'::jsonb, INTERVAL '7 days'",
                "repeat('a',64), 204, '{}'::jsonb, INTERVAL '7 days'",
                "repeat('a',64), 204, '{\"status\":204}'::jsonb, INTERVAL '6 days'")) {
            assertThatThrownBy(() -> jdbcTemplate.update(
                            """
                    INSERT INTO inventory.inventory_status_transition_requests
                    SELECT gen_random_uuid(), candidate.fingerprint, candidate.status, candidate.outcome,
                        statement_timestamp(), statement_timestamp() + candidate.retention
                    FROM (VALUES (
                    """ + invalid + ")) AS candidate(fingerprint, status, outcome, retention)"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void createsTheHistoryTableWithDatabaseGeneratedValuesAndNoItemForeignKey() {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                INSERT INTO inventory.inventory_status_history (serial_number, status_from, status_to)
                VALUES ('DRILL-HISTORY', 'AVAILABLE', 'RESERVED')
                RETURNING id, transitioned_at
                """);

        assertThat(row.get("id")).isInstanceOf(Long.class);
        assertThat(row.get("transitioned_at")).isNotNull();
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT count(*)
                        FROM information_schema.columns
                        WHERE table_schema = 'inventory'
                          AND table_name = 'inventory_status_history'
                          AND column_name = 'id'
                          AND data_type = 'bigint'
                          AND is_identity = 'YES'
                        """, Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT indexdef
                        FROM pg_indexes
                        WHERE schemaname = 'inventory'
                          AND tablename = 'inventory_status_history'
                          AND indexname = 'idx_inventory_status_history_transitioned_at_id'
                        """, String.class)).contains("transitioned_at DESC", "id DESC");
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT count(*)
                        FROM information_schema.columns
                        WHERE table_schema = 'inventory'
                          AND table_name = 'inventory_status_history'
                          AND column_name = 'transitioned_at'
                          AND data_type = 'timestamp with time zone'
                          AND column_default IS NOT NULL
                        """, Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT count(*)
                        FROM information_schema.table_constraints
                        WHERE table_schema = 'inventory'
                          AND table_name = 'inventory_status_history'
                          AND constraint_type = 'FOREIGN KEY'
                        """, Integer.class)).isZero();
    }

    @Test
    void historyConstraintsRejectInvalidRows() {
        assertInvalidHistoryRow("/INVALID", "AVAILABLE", "RESERVED");
        assertInvalidHistoryRow("S".repeat(65), "AVAILABLE", "RESERVED");
        assertInvalidHistoryRow("DRILL-HISTORY", "UNKNOWN", "RESERVED");
        assertInvalidHistoryRow("DRILL-HISTORY", "AVAILABLE", "UNKNOWN");
        assertInvalidHistoryRow("DRILL-HISTORY", "AVAILABLE", "AVAILABLE");
        assertInvalidHistoryRow(null, "AVAILABLE", "RESERVED");
        assertInvalidHistoryRow("DRILL-HISTORY", null, "RESERVED");
        assertInvalidHistoryRow("DRILL-HISTORY", "AVAILABLE", null);
    }

    @Test
    void retainedHistorySurvivesItemDeletionAndASecondApplicationContext() {
        repository.saveAndFlush(new InventoryItem("DRILL-AUDIT", "Drill", "Audit", InventoryStatus.AVAILABLE));
        historyRepository.saveAndFlush(
                new InventoryStatusHistory("DRILL-AUDIT", InventoryStatus.AVAILABLE, InventoryStatus.RESERVED));
        repository.deleteAllInBatch();

        assertThat(historyRepository.count()).isOne();
        try (ConfigurableApplicationContext secondContext = startApplication(Map.of())) {
            InventoryStatusHistoryRepository secondHistoryRepository =
                    secondContext.getBean(InventoryStatusHistoryRepository.class);
            assertThat(secondHistoryRepository.count()).isOne();
        }
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

        try (ConfigurableApplicationContext secondContext = startApplication(Map.of())) {
            InventoryRepository secondRepository = secondContext.getBean(InventoryRepository.class);
            assertThat(secondRepository.findById("DRILL-RESTART")).isPresent();
        }
    }

    @Test
    void startupFailsWhenTheOwnedSchemaIsMissing() {
        assertThatThrownBy(() -> {
                    try (ConfigurableApplicationContext ignored = startApplication(Map.of(
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

    private void assertInvalidHistoryRow(String serialNumber, String statusFrom, String statusTo) {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO inventory.inventory_status_history (serial_number, status_from, status_to)
                        VALUES (?, ?, ?)
                        """, serialNumber, statusFrom, statusTo))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private ConfigurableApplicationContext startApplication(Map<String, Object> overrides) {
        HashMap<String, Object> properties = new HashMap<>();
        properties.put("spring.datasource.url", POSTGRES.getJdbcUrl());
        properties.put("spring.datasource.username", INVENTORY_USERNAME);
        properties.put("spring.datasource.password", INVENTORY_PASSWORD);
        properties.put("spring.main.banner-mode", "off");
        properties.put("logging.level.root", "OFF");
        properties.putAll(overrides);
        String[] arguments = properties.entrySet().stream()
                .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);

        return new SpringApplicationBuilder(InventoryApplication.class)
                .web(WebApplicationType.NONE)
                .run(arguments);
    }
}
