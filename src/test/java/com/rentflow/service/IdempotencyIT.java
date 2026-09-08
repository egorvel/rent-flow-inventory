package com.rentflow.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.rentflow.InventoryApplication;
import com.rentflow.model.InventoryItem;
import com.rentflow.model.InventoryStatus;
import com.rentflow.model.InventoryStatusTransition;
import com.rentflow.repository.InventoryRepository;
import com.rentflow.repository.InventoryStatusHistoryRepository;
import com.rentflow.repository.InventoryStatusTransitionRequestRepository;
import com.rentflow.support.PostgresIntegrationTest;

import io.micrometer.core.instrument.MeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IdempotencyIT extends PostgresIntegrationTest {
    private static final String PATH = "/api/v1/inventory/status";
    private static final String BODY = "[{\"serialNumber\":\"A\",\"status\":\"RESERVED\"}]";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private InventoryRepository inventory;

    @Autowired
    private InventoryStatusHistoryRepository history;

    @Autowired
    private InventoryStatusTransitionRequestRepository requests;

    @Autowired
    private IdempotencyCleanupService cleanup;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MeterRegistry metrics;

    @BeforeEach
    void reset() {
        requests.deleteAllInBatch();
        history.deleteAllInBatch();
        inventory.deleteAllInBatch();
        inventory.saveAndFlush(new InventoryItem("A", "Drill", "One", InventoryStatus.AVAILABLE));
    }

    @Test
    void replaysCommittedSuccessAfterChangedAndDeletedItemsWithStableExpiry() throws Exception {
        UUID key = UUID.randomUUID();
        double attempts = metrics.counter("inventory.idempotency.requests", "outcome", "attempt")
                .count();
        Instant before = requests.databaseTime();
        MvcResult first = send(key, BODY)
                .andExpect(status().isNoContent())
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andReturn();
        Instant after = requests.databaseTime();
        String expiry = first.getResponse().getHeader("Idempotency-Key-Expires-At");
        assertThat(metrics.counter("inventory.idempotency.requests", "outcome", "attempt")
                        .count())
                .isEqualTo(attempts + 1);
        assertThat(Instant.parse(expiry)).isBetween(before.plus(Duration.ofDays(7)), after.plus(Duration.ofDays(7)));
        assertThat(history.count()).isOne();
        jdbc.update("UPDATE inventory.inventory_items SET status = 'RENTED' WHERE serial_number = 'A'");
        send(key, BODY).andExpect(status().isNoContent()).andExpect(header().string("Idempotency-Replayed", "true"));
        inventory.deleteAllInBatch();
        send(key, BODY)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(header().string("Idempotency-Key-Expires-At", expiry));
        assertThat(history.count()).isOne();
        assertThat(requests.count()).isOne();
    }

    @Test
    void normalizesHeaderCaseAndJsonFormattingButRejectsPayloadChanges() throws Exception {
        UUID key = UUID.randomUUID();
        send(key, BODY).andExpect(status().isNoContent());
        mvc.perform(patch(PATH)
                        .header("Idempotency-Key", key.toString().toUpperCase(java.util.Locale.ROOT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[ { \"status\": \"RESERVED\", \"serialNumber\": \"A\" } ]"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Idempotency-Replayed", "true"));
        for (String body : List.of(BODY.replace("RESERVED", "RENTED"), BODY.replace("\"A\"", "\"a\""))) {
            send(key, body)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
                    .andExpect(header().doesNotExist("Idempotency-Replayed"));
        }
        assertThat(history.count()).isOne();
    }

    @Test
    void preservesArrayOrderInIdentity() throws Exception {
        inventory.saveAndFlush(new InventoryItem("B", "Drill", "Two", InventoryStatus.AVAILABLE));
        UUID key = UUID.randomUUID();
        String a = BODY.substring(1, BODY.length() - 1);
        String b = a.replace("A", "B");
        send(key, "[" + a + "," + b + "]").andExpect(status().isNoContent());
        send(key, "[" + b + "," + a + "]").andExpect(status().isUnprocessableContent());
        assertThat(history.count()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "conflict", "mixed"})
    void replaysEveryTerminalRejectionWithoutReevaluation(String kind) throws Exception {
        UUID key = UUID.randomUUID();
        String body =
                switch (kind) {
                    case "missing" -> BODY.replace("A", "MISSING");
                    case "conflict" -> BODY.replace("RESERVED", "AVAILABLE");
                    default ->
                        "[{\"serialNumber\":\"A\",\"status\":\"AVAILABLE\"},{\"serialNumber\":\"MISSING\",\"status\":\"RESERVED\"}]";
                };
        MvcResult first = send(key, body)
                .andExpect(status().is(kind.equals("missing") ? 404 : 409))
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andReturn();
        inventory.saveAndFlush(new InventoryItem("MISSING", "Drill", "Now exists", InventoryStatus.AVAILABLE));
        jdbc.update("UPDATE inventory.inventory_items SET status = 'RESERVED' WHERE serial_number = 'A'");
        send(key, body)
                .andExpect(status().is(first.getResponse().getStatus()))
                .andExpect(content().json(first.getResponse().getContentAsString()))
                .andExpect(header().string("Idempotency-Replayed", "true"));
        assertThat(history.count()).isZero();
    }

    @Test
    void rejectsMissingRepeatedAndInvalidKeysBeforeLedgerAccess() throws Exception {
        mvc.perform(patch(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("Idempotency-Key"));
        for (String value : List.of(
                "",
                "not-a-uuid",
                "1-1-4-8-1",
                "00000000-0000-1000-8000-000000000000",
                "00000000-0000-4000-0000-000000000000",
                "\"" + UUID.randomUUID() + "\"")) {
            mvc.perform(patch(PATH)
                            .header("Idempotency-Key", value)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.violations[0].field").value("Idempotency-Key"));
        }
        String key = UUID.randomUUID().toString();
        mvc.perform(patch(PATH)
                        .header("Idempotency-Key", key, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("Idempotency-Key"));
        assertThat(requests.count()).isZero();
        assertThat(history.count()).isZero();
    }

    @Test
    void invalidInputDoesNotConsumeKeyAndPrecedesReplayOrMismatch() throws Exception {
        UUID key = UUID.randomUUID();
        send(key, "[]").andExpect(status().isBadRequest());
        assertThat(requests.count()).isZero();
        send(key, BODY).andExpect(status().isNoContent());
        send(key, "[]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(requests.count()).isOne();
    }

    @Test
    void heldExecutionLockRejectsImmediatelyEvenForDifferentPayloadWhileOtherKeysProceed() {
        UUID key = UUID.randomUUID();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            tx.executeWithoutResult(ignored -> {
                assertThat(requests.tryExecutionLock(IdempotencyFingerprint.lockId(key)))
                        .isTrue();
                try {
                    executor.submit(() -> {
                                send(key, BODY)
                                        .andExpect(status().isConflict())
                                        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_IN_PROGRESS"))
                                        .andExpect(header().string("Retry-After", "1"))
                                        .andExpect(header().doesNotExist("Idempotency-Key-Expires-At"));
                                send(key, BODY.replace("RESERVED", "RENTED"))
                                        .andExpect(status().isConflict())
                                        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_IN_PROGRESS"));
                                send(UUID.randomUUID(), BODY).andExpect(status().isNoContent());
                                return null;
                            })
                            .get(3, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
                assertThat(requests.existsById(key)).isFalse();
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"inventory_items", "inventory_status_history", "inventory_status_transition_requests"})
    void anyPersistenceFailureRollsBackEverythingAndAllowsSameKeyRetry(String table) throws Exception {
        UUID key = UUID.randomUUID();
        jdbc.execute(
                "CREATE FUNCTION inventory.reject_idempotency_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'forced failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_idempotency_test BEFORE INSERT OR UPDATE ON inventory." + table
                + " FOR EACH ROW EXECUTE FUNCTION inventory.reject_idempotency_test()");
        try {
            send(key, BODY)
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
            assertThat(requests.count()).isZero();
            assertThat(history.count()).isZero();
            assertThat(inventory.findById("A").orElseThrow().getStatus()).isEqualTo(InventoryStatus.AVAILABLE);
        } finally {
            jdbc.execute("DROP TRIGGER reject_idempotency_test ON inventory." + table);
            jdbc.execute("DROP FUNCTION inventory.reject_idempotency_test()");
        }
        send(key, BODY).andExpect(status().isNoContent());
        assertThat(history.count()).isOne();
    }

    @Test
    void expiryAllowsChangedPayloadBeforePhysicalCleanup() throws Exception {
        UUID key = UUID.randomUUID();
        send(key, BODY).andExpect(status().isNoContent());
        jdbc.update(
                "UPDATE inventory.inventory_status_transition_requests SET recorded_at = recorded_at - INTERVAL '8 days', expires_at = expires_at - INTERVAL '8 days'");
        assertThat(cleanup.countExpired()).isOne();
        send(key, BODY.replace("RESERVED", "RENTED"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Idempotency-Replayed", "false"));
        assertThat(requests.count()).isOne();
        assertThat(cleanup.countExpired()).isZero();
        assertThat(history.count()).isEqualTo(2);
    }

    @Test
    void committedOutcomeSurvivesApplicationRestart() throws Exception {
        UUID key = UUID.randomUUID();
        send(key, BODY).andExpect(status().isNoContent());
        try (ConfigurableApplicationContext second = new SpringApplicationBuilder(InventoryApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + INVENTORY_USERNAME,
                        "--spring.datasource.password=" + INVENTORY_PASSWORD,
                        "--logging.level.root=OFF",
                        "--spring.main.banner-mode=off")) {
            IdempotentStatusTransitionService.Result result = second.getBean(IdempotentStatusTransitionService.class)
                    .transition(key, List.of(new InventoryStatusTransition("A", InventoryStatus.RESERVED)));
            assertThat(result.replayed()).isTrue();
            assertThat(result.outcome().status()).isEqualTo(204);
        }
        assertThat(history.count()).isOne();
    }

    @Test
    void cleanupChunksSkipLockedRowsAndPreserveUnexpiredRequestsAndHistory() throws Exception {
        send(UUID.randomUUID(), BODY).andExpect(status().isNoContent());
        insertExpired(2001);
        UUID locked = jdbc.queryForObject(
                "SELECT idempotency_key FROM inventory.inventory_status_transition_requests WHERE expires_at < clock_timestamp() ORDER BY idempotency_key LIMIT 1",
                UUID.class);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            tx.executeWithoutResult(ignored -> {
                requests.findForUpdateByKey(locked).orElseThrow();
                try {
                    assertThat(executor.submit(() -> cleanup.deleteChunk()).get(3, TimeUnit.SECONDS))
                            .isEqualTo(1000);
                    assertThat(executor.submit(() -> cleanup.deleteChunk()).get(3, TimeUnit.SECONDS))
                            .isEqualTo(1000);
                    assertThat(executor.submit(() -> cleanup.deleteChunk()).get(3, TimeUnit.SECONDS))
                            .isZero();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            });
        }
        assertThat(cleanup.deleteChunk()).isOne();
        assertThat(requests.count()).isOne();
        assertThat(history.count()).isOne();
    }

    @Test
    void concurrentCleanupWorkersDeleteEachExpiredRowOnce() throws Exception {
        insertExpired(2000);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Future<Integer> first = executor.submit(() -> cleanup.deleteChunk());
            java.util.concurrent.Future<Integer> second = executor.submit(() -> cleanup.deleteChunk());
            assertThat(first.get(3, TimeUnit.SECONDS) + second.get(3, TimeUnit.SECONDS))
                    .isEqualTo(2000);
        }
        assertThat(requests.count()).isZero();
    }

    private void insertExpired(int count) {
        jdbc.update("""
                INSERT INTO inventory.inventory_status_transition_requests
                    (idempotency_key, fingerprint, http_status, outcome, recorded_at, expires_at)
                SELECT md5(n::text)::uuid, repeat('a',64), 204, '{"status":204,"failedItems":[]}'::jsonb,
                    statement_timestamp() - INTERVAL '8 days', statement_timestamp() - INTERVAL '1 day'
                FROM generate_series(1, ?) n
                """, count);
    }

    private ResultActions send(UUID key, String body) throws Exception {
        return mvc.perform(patch(PATH)
                .header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
