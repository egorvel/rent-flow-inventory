package com.rentflow;

import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
class SecurityMigrationIT {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("rentflow")
            .withUsername("rentflow_admin")
            .withPassword("rentflow-admin-test")
            .withInitScript("testcontainers/init-inventory.sql");

    @Test
    void upgradesLegacyLedgerWithoutLosingOutcomesOrOtherServiceObjects() {
        DriverManagerDataSource datasource =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "inventory", "inventory-test");
        JdbcTemplate jdbc = new JdbcTemplate(datasource);
        Flyway.configure()
                .dataSource(datasource)
                .defaultSchema("inventory")
                .schemas("inventory")
                .createSchemas(false)
                .target("4")
                .load()
                .migrate();
        UUID key = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inventory.inventory_status_transition_requests
                    (idempotency_key,fingerprint,http_status,outcome,recorded_at,expires_at)
                VALUES (?,repeat('a',64),204,'{"status":204,"failedItems":[]}'::jsonb,
                    statement_timestamp(),statement_timestamp()+interval '7 days')
                """, key);
        java.util.Map<String, Object> original = jdbc.queryForMap(
                "SELECT outcome::text,expires_at FROM inventory.inventory_status_transition_requests WHERE idempotency_key=?",
                key);
        Flyway upgraded = Flyway.configure()
                .dataSource(datasource)
                .defaultSchema("inventory")
                .schemas("inventory")
                .createSchemas(false)
                .load();
        assertThat(upgraded.migrate().migrationsExecuted).isOne();
        assertThat(jdbc.queryForMap(
                        "SELECT outcome::text,expires_at FROM inventory.inventory_status_transition_requests WHERE caller_scope='legacy:unauthenticated' AND idempotency_key=?",
                        key))
                .isEqualTo(original);
        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.key_column_usage
                WHERE table_schema='inventory' AND constraint_name='inventory_status_transition_requests_pkey'
                ORDER BY ordinal_position
                """, String.class)).containsExactly("caller_scope", "idempotency_key");
        assertThat(jdbc.queryForObject(
                        "SELECT column_default FROM information_schema.columns WHERE table_schema='inventory' AND table_name='inventory_status_transition_requests' AND column_name='caller_scope'",
                        String.class))
                .isNull();
        for (String scope : List.of("p1:" + "a".repeat(64), "p1:" + "b".repeat(64))) {
            jdbc.update("""
                    INSERT INTO inventory.inventory_status_transition_requests
                    SELECT idempotency_key,fingerprint,http_status,outcome,recorded_at,expires_at,?
                    FROM inventory.inventory_status_transition_requests WHERE caller_scope='legacy:unauthenticated'
                    """, scope);
        }
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM inventory.inventory_status_transition_requests WHERE idempotency_key=?",
                        Integer.class,
                        key))
                .isEqualTo(3);
        assertThatThrownBy(
                        () -> jdbc.update(
                                "UPDATE inventory.inventory_status_transition_requests SET caller_scope='p1:invalid' WHERE caller_scope='legacy:unauthenticated'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(upgraded.migrate().migrationsExecuted).isZero();
        JdbcTemplate admin = new JdbcTemplate(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        assertThat(admin.queryForObject("SELECT marker FROM rental.sentinel WHERE id=1", String.class))
                .isEqualTo("untouched");
        assertThat(jdbc.queryForObject(
                        "SELECT tableowner FROM pg_tables WHERE schemaname='inventory' AND tablename='inventory_status_transition_requests'",
                        String.class))
                .isEqualTo("inventory");
    }
}
