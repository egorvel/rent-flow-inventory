package com.rentflow.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
public abstract class PostgresIntegrationTest {

    protected static final String INVENTORY_USERNAME = "inventory";
    protected static final String INVENTORY_PASSWORD = "inventory-test";

    @Container
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("rentflow")
            .withUsername("rentflow_admin")
            .withPassword("rentflow-admin-test")
            .withInitScript("testcontainers/init-inventory.sql");

    @DynamicPropertySource
    static void registerDatasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> INVENTORY_USERNAME);
        registry.add("spring.datasource.password", () -> INVENTORY_PASSWORD);
    }
}
