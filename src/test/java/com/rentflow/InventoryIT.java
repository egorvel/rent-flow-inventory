package com.rentflow;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.rentflow.model.InventoryItem;
import com.rentflow.model.InventoryStatus;
import com.rentflow.model.InventoryStatusHistory;
import com.rentflow.repository.InventoryRepository;
import com.rentflow.repository.InventoryStatusHistoryRepository;
import com.rentflow.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InventoryIT extends PostgresIntegrationTest {

    private static final String SERIAL_NUMBER = "DRILL-CONFLICT";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InventoryRepository repository;

    @Autowired
    private InventoryStatusHistoryRepository historyRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearInventory() {
        historyRepository.deleteAllInBatch();
        repository.deleteAllInBatch();
    }

    @Test
    void createsAndRetrievesANormalizedItemWithoutCredentials() throws Exception {
        mockMvc.perform(post("/api/v1/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest("DRILL-001", "  Industrial drill  ", "  Bosch GBH  ")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/inventory/DRILL-001"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.serialNumber").value("DRILL-001"))
                .andExpect(jsonPath("$.type").value("Industrial drill"))
                .andExpect(jsonPath("$.name").value("Bosch GBH"))
                .andExpect(jsonPath("$.status").value("AVAILABLE"));

        mockMvc.perform(get("/api/v1/inventory/DRILL-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serialNumber").value("DRILL-001"))
                .andExpect(jsonPath("$.type").value("Industrial drill"))
                .andExpect(jsonPath("$.name").value("Bosch GBH"))
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
    }

    @Test
    void keepsSerialNumbersCaseSensitive() throws Exception {
        mockMvc.perform(post("/api/v1/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest("DRILL-001", "Drill", "Upper")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest("drill-001", "Drill", "Lower")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/inventory/DRILL-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Upper"));
        mockMvc.perform(get("/api/v1/inventory/drill-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Lower"));
    }

    @Test
    void rejectsInvalidCreateBodiesWithoutPersisting() throws Exception {
        mockMvc.perform(post("/api/v1/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field").value("name"))
                .andExpect(jsonPath("$.violations[1].field").value("serialNumber"))
                .andExpect(jsonPath("$.violations[2].field").value("status"))
                .andExpect(jsonPath("$.violations[3].field").value("type"));
        assertInvalidCreate("""
                {"serialNumber":" bad ","type":" ","name":" ","status":"AVAILABLE"}
                """);
        assertInvalidCreate("""
                {"serialNumber":"%s","type":"%s","name":"%s","status":"AVAILABLE"}
                """.formatted("S".repeat(65), "T".repeat(101), "N".repeat(201)));
        assertInvalidCreate("""
                {"serialNumber":"DRILL-001","type":"Drill","name":"Name","status":"UNKNOWN"}
                """);
        assertInvalidCreate("""
                {"serialNumber":"DRILL-001","type":"Drill","name":"Name","status":"AVAILABLE","extra":true}
                """);
        assertInvalidCreate("{");

        org.assertj.core.api.Assertions.assertThat(repository.count()).isZero();
    }

    @Test
    void rejectsInvalidAndMissingSerialNumbers() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/%20bad%20"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(get("/api/v1/inventory/MISSING"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:rentflow:problem:inventory-item-not-found"))
                .andExpect(jsonPath("$.code").value("INVENTORY_ITEM_NOT_FOUND"))
                .andExpect(jsonPath("$.violations").doesNotExist());
    }

    @Test
    void replacesAndPersistsAllMutableFieldsWhilePreservingIdentity() throws Exception {
        repository.saveAndFlush(new InventoryItem("DRILL-001", "Drill", "Original", InventoryStatus.AVAILABLE));

        mockMvc.perform(put("/api/v1/inventory/DRILL-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest(
                                "DRILL-001", "  Industrial drill  ", "  Updated drill  ", "UNDER_MAINTENANCE")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.serialNumber").value("DRILL-001"))
                .andExpect(jsonPath("$.type").value("Industrial drill"))
                .andExpect(jsonPath("$.name").value("Updated drill"))
                .andExpect(jsonPath("$.status").value("UNDER_MAINTENANCE"));

        InventoryItem persisted = repository.findById("DRILL-001").orElseThrow();
        org.assertj.core.api.Assertions.assertThat(persisted.getSerialNumber()).isEqualTo("DRILL-001");
        org.assertj.core.api.Assertions.assertThat(persisted.getType()).isEqualTo("Industrial drill");
        org.assertj.core.api.Assertions.assertThat(persisted.getName()).isEqualTo("Updated drill");
        org.assertj.core.api.Assertions.assertThat(persisted.getStatus()).isEqualTo(InventoryStatus.UNDER_MAINTENANCE);
        assertThat(historyRepository.count()).isZero();
    }

    @Test
    void acceptsEveryDefinedStatusFromEveryPriorStatus() throws Exception {
        for (InventoryStatus priorStatus : InventoryStatus.values()) {
            for (InventoryStatus replacementStatus : InventoryStatus.values()) {
                repository.deleteAllInBatch();
                repository.saveAndFlush(new InventoryItem("DRILL-001", "Drill", "Original", priorStatus));

                mockMvc.perform(put("/api/v1/inventory/DRILL-001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validRequest("DRILL-001", "Drill", "Updated", replacementStatus.name())))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value(replacementStatus.name()));

                org.assertj.core.api.Assertions.assertThat(
                                repository.findById("DRILL-001").orElseThrow().getStatus())
                        .isEqualTo(replacementStatus);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("transitionCases")
    void enforcesEveryDedicatedStatusTransition(InventoryStatus source, InventoryStatus target, boolean permitted)
            throws Exception {
        repository.saveAndFlush(new InventoryItem("DRILL-001", "Drill", "Original", source));
        Instant before = Instant.now();

        ResultActions response = mockMvc.perform(patch("/api/v1/inventory/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(statusUpdateRequest("DRILL-001", target)));

        if (permitted) {
            response.andExpect(status().isNoContent()).andExpect(content().string(""));
            InventoryItem item = repository.findById("DRILL-001").orElseThrow();
            assertThat(item.getStatus()).isEqualTo(target);
            assertThat(item.getType()).isEqualTo("Drill");
            assertThat(item.getName()).isEqualTo("Original");
            assertThat(historyRepository.findAll()).singleElement().satisfies(history -> {
                assertThat(history.getSerialNumber()).isEqualTo("DRILL-001");
                assertThat(history.getStatusFrom()).isEqualTo(source);
                assertThat(history.getStatusTo()).isEqualTo(target);
                assertThat(history.getTimestamp()).isBetween(before, Instant.now());
            });
        } else {
            response.andExpect(status().isConflict())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("urn:rentflow:problem:invalid-inventory-status-transition"))
                    .andExpect(jsonPath("$.code").value("INVALID_INVENTORY_STATUS_TRANSITION"))
                    .andExpect(jsonPath("$.detail").value("No inventory statuses were changed."))
                    .andExpect(jsonPath("$.failedItems.length()").value(1))
                    .andExpect(jsonPath("$.failedItems[0].index").value(0))
                    .andExpect(jsonPath("$.failedItems[0].serialNumber").value("DRILL-001"))
                    .andExpect(jsonPath("$.failedItems[0].status").value(target.name()))
                    .andExpect(jsonPath("$.failedItems[0].code").value("INVALID_INVENTORY_STATUS_TRANSITION"))
                    .andExpect(jsonPath("$.failedItems[0].message")
                            .value("Inventory item 'DRILL-001' cannot transition from " + source + " to " + target
                                    + "."));
            assertThat(repository.findById("DRILL-001").orElseThrow().getStatus())
                    .isEqualTo(source);
            assertThat(historyRepository.count()).isZero();
        }
    }

    @Test
    void rejectsInvalidTransitionRequestsWithoutHistory() throws Exception {
        repository.saveAndFlush(new InventoryItem("DRILL-001", "Drill", "Original", InventoryStatus.AVAILABLE));
        for (String body :
                List.of("[{\"serialNumber\":\"DRILL-001\"}]", "[{\"serialNumber\":\"DRILL-001\",\"status\":null}]")) {
            expectValidation(
                            mockMvc.perform(patch("/api/v1/inventory/status")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body)),
                            "/api/v1/inventory/status")
                    .andExpect(jsonPath("$.violations[0].field").value("[0].status"));
        }
        expectValidation(
                        mockMvc.perform(patch("/api/v1/inventory/status")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("[{\"serialNumber\":\"DRILL-001\",\"status\":\"UNKNOWN\"}]")),
                        "/api/v1/inventory/status")
                .andExpect(jsonPath("$.violations[0].field").value("status"));
        expectProblem(
                mockMvc.perform(patch("/api/v1/inventory/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"serialNumber\":\"DRILL-001\",\"status\":\"RESERVED\",\"extra\":true}]")),
                HttpStatus.BAD_REQUEST,
                "urn:rentflow:problem:malformed-json",
                "Malformed JSON",
                "The request body could not be read.",
                "/api/v1/inventory/status",
                "MALFORMED_JSON");
        expectProblem(
                mockMvc.perform(patch("/api/v1/inventory/status")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("RESERVED")),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "urn:rentflow:problem:unsupported-media-type",
                "Unsupported media type",
                "The request media type is not supported.",
                "/api/v1/inventory/status",
                "UNSUPPORTED_MEDIA_TYPE");
        mockMvc.perform(patch("/api/v1/inventory/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusUpdateRequest("MISSING", InventoryStatus.RESERVED)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$", aMapWithSize(7)))
                .andExpect(jsonPath("$.type").value("urn:rentflow:problem:inventory-item-not-found"))
                .andExpect(jsonPath("$.title").value("Inventory item not found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("No inventory statuses were changed."))
                .andExpect(jsonPath("$.instance").value("/api/v1/inventory/status"))
                .andExpect(jsonPath("$.code").value("INVENTORY_ITEM_NOT_FOUND"))
                .andExpect(jsonPath("$.failedItems.length()").value(1))
                .andExpect(jsonPath("$.failedItems[0]", aMapWithSize(5)))
                .andExpect(jsonPath("$.failedItems[0].index").value(0))
                .andExpect(jsonPath("$.failedItems[0].serialNumber").value("MISSING"))
                .andExpect(jsonPath("$.failedItems[0].status").value("RESERVED"))
                .andExpect(jsonPath("$.failedItems[0].code").value("INVENTORY_ITEM_NOT_FOUND"))
                .andExpect(jsonPath("$.failedItems[0].message").value("Inventory item 'MISSING' was not found."));
        assertThat(historyRepository.count()).isZero();
        assertThat(repository.findById("DRILL-001").orElseThrow().getStatus()).isEqualTo(InventoryStatus.AVAILABLE);
    }

    @Test
    void reportsEveryFailedItemInRequestOrderAndRollsBackValidEntries() throws Exception {
        repository.saveAndFlush(new InventoryItem("A-VALID", "Drill", "Original", InventoryStatus.AVAILABLE));
        repository.saveAndFlush(new InventoryItem("Z-RETIRED", "Drill", "Original", InventoryStatus.RETIRED));
        repository.saveAndFlush(new InventoryItem("S-SAME", "Drill", "Original", InventoryStatus.RESERVED));
        mockMvc.perform(patch("/api/v1/inventory/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                [
                  {"serialNumber":"Z-RETIRED","status":"AVAILABLE"},
                  {"serialNumber":"A-VALID","status":"RENTED"},
                  {"serialNumber":"MISSING","status":"RESERVED"},
                  {"serialNumber":"S-SAME","status":"RESERVED"}
                ]
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_INVENTORY_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.failedItems.length()").value(3))
                .andExpect(jsonPath("$.failedItems[*].index", contains(0, 2, 3)))
                .andExpect(jsonPath("$.failedItems[*].serialNumber", contains("Z-RETIRED", "MISSING", "S-SAME")))
                .andExpect(jsonPath("$.failedItems[*].status", contains("AVAILABLE", "RESERVED", "RESERVED")))
                .andExpect(jsonPath(
                        "$.failedItems[*].code",
                        contains(
                                "INVALID_INVENTORY_STATUS_TRANSITION",
                                "INVENTORY_ITEM_NOT_FOUND",
                                "INVALID_INVENTORY_STATUS_TRANSITION")))
                .andExpect(jsonPath(
                        "$.failedItems[*].message",
                        contains(
                                "Inventory item 'Z-RETIRED' cannot transition from RETIRED to AVAILABLE.",
                                "Inventory item 'MISSING' was not found.",
                                "Inventory item 'S-SAME' cannot transition from RESERVED to RESERVED.")))
                .andExpect(jsonPath("$.violations").doesNotExist());
        assertThat(repository.findById("A-VALID").orElseThrow().getStatus()).isEqualTo(InventoryStatus.AVAILABLE);
        assertThat(repository.findById("Z-RETIRED").orElseThrow().getStatus()).isEqualTo(InventoryStatus.RETIRED);
        assertThat(repository.findById("S-SAME").orElseThrow().getStatus()).isEqualTo(InventoryStatus.RESERVED);
        assertThat(repository.count()).isEqualTo(3);
        assertThat(historyRepository.count()).isZero();

        mockMvc.perform(patch("/api/v1/inventory/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                [{"serialNumber":"Z-MISSING","status":"RESERVED"},
                 {"serialNumber":"A-VALID","status":"RENTED"},
                 {"serialNumber":"B-MISSING","status":"RENTED"}]
                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVENTORY_ITEM_NOT_FOUND"))
                .andExpect(jsonPath("$.failedItems[*].index", contains(0, 2)))
                .andExpect(jsonPath("$.failedItems[*].serialNumber", contains("Z-MISSING", "B-MISSING")));
        assertThat(repository.findById("A-VALID").orElseThrow().getStatus()).isEqualTo(InventoryStatus.AVAILABLE);
        assertThat(repository.count()).isEqualTo(3);
        assertThat(historyRepository.count()).isZero();
    }

    @ParameterizedTest
    @MethodSource("invalidBatchBodies")
    void rejectsBatchInputBeforeEvaluatingLifecycleRules(String body) throws Exception {
        repository.saveAndFlush(new InventoryItem("DRILL-001", "Drill", "Original", InventoryStatus.RETIRED));
        mockMvc.perform(patch("/api/v1/inventory/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.failedItems").doesNotExist());
        assertThat(repository.findById("DRILL-001").orElseThrow().getStatus()).isEqualTo(InventoryStatus.RETIRED);
        assertThat(historyRepository.count()).isZero();
    }

    private static Stream<String> invalidBatchBodies() {
        String conflicting = "{\"serialNumber\":\"DRILL-001\",\"status\":\"AVAILABLE\"}";
        return Stream.of(
                "",
                "null",
                "{}",
                "[]",
                "[null]",
                "[",
                "[{}]",
                "[" + conflicting + "," + conflicting + "]",
                "[" + conflicting + ",{\"serialNumber\":\"DRILL-001\",\"status\":\"RENTED\"}]",
                "[" + conflicting + ",{\"status\":\"RENTED\"}]",
                "[" + conflicting + ",{\"serialNumber\":null,\"status\":\"RENTED\"}]",
                "[" + conflicting + ",{\"serialNumber\":\"bad serial\",\"status\":\"RENTED\"}]",
                "[{\"serialNumber\":\"" + "A".repeat(65) + "\",\"status\":\"RENTED\"}]",
                "[{\"serialNumber\":\" \",\"status\":\"RENTED\"}]",
                "[" + conflicting + ",{\"serialNumber\":\"VALID\",\"status\":\"UNKNOWN\"}]",
                "[" + conflicting + ",{\"serialNumber\":\"VALID\",\"status\":null}]");
    }

    @Test
    void acceptsOneHundredItemsAndRejectsOneHundredAndOne() throws Exception {
        List<String> entries = new java.util.ArrayList<>();
        for (int index = 0; index < 101; index++) {
            String serial = "ITEM-" + index;
            repository.saveAndFlush(new InventoryItem(serial, "Drill", "Original", InventoryStatus.AVAILABLE));
            entries.add("{\"serialNumber\":\"" + serial + "\",\"status\":\"RENTED\"}");
        }
        mockMvc.perform(patch("/api/v1/inventory/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[" + String.join(",", entries) + "]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("request"));
        assertThat(repository.findAll())
                .allSatisfy(item -> assertThat(item.getStatus()).isEqualTo(InventoryStatus.AVAILABLE));
        assertThat(historyRepository.count()).isZero();
        Instant before = Instant.now();
        mockMvc.perform(patch("/api/v1/inventory/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[" + String.join(",", entries.subList(0, 100)) + "]"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        assertThat(historyRepository.findAll()).hasSize(100).allSatisfy(history -> {
            assertThat(history.getStatusFrom()).isEqualTo(InventoryStatus.AVAILABLE);
            assertThat(history.getStatusTo()).isEqualTo(InventoryStatus.RENTED);
            assertThat(history.getTimestamp()).isBetween(before, Instant.now());
        });
        assertThat(historyRepository.findAll())
                .extracting(InventoryStatusHistory::getSerialNumber)
                .doesNotHaveDuplicates();
        for (int index = 0; index < 101; index++) {
            InventoryItem item = repository.findById("ITEM-" + index).orElseThrow();
            assertThat(item.getStatus()).isEqualTo(index < 100 ? InventoryStatus.RENTED : InventoryStatus.AVAILABLE);
            assertThat(item.getType()).isEqualTo("Drill");
            assertThat(item.getName()).isEqualTo("Original");
        }
    }

    @Test
    void removesTheOldRouteAndPreservesCrudForSerialStatus() throws Exception {
        repository.saveAndFlush(new InventoryItem("status", "Drill", "Original", InventoryStatus.AVAILABLE));
        mockMvc.perform(patch("/api/v1/inventory/status/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RENTED\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/inventory/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serialNumber").value("status"));
        mockMvc.perform(put("/api/v1/inventory/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest("status", "Drill", "Updated", "RETIRED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETIRED"));
        mockMvc.perform(delete("/api/v1/inventory/status")).andExpect(status().isNoContent());
        assertThat(historyRepository.count()).isZero();
    }

    @Test
    void rollsBackBothTablesWhenEitherWriteFails() throws Exception {
        repository.saveAndFlush(new InventoryItem("DRILL-001", "Drill", "Original", InventoryStatus.AVAILABLE));

        repository.saveAndFlush(new InventoryItem("SECOND", "Mixer", "Original", InventoryStatus.AVAILABLE));
        String batch = "[{\"serialNumber\":\"DRILL-001\",\"status\":\"RESERVED\"},"
                + "{\"serialNumber\":\"SECOND\",\"status\":\"RENTED\"}]";

        createFailingTrigger("inventory_status_history", "fail_history_insert", "INSERT");
        try {
            mockMvc.perform(patch("/api/v1/inventory/status")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(batch))
                    .andExpect(status().isInternalServerError());
        } finally {
            dropFailingTrigger("inventory_status_history", "fail_history_insert");
        }
        assertThat(repository.findById("DRILL-001").orElseThrow().getStatus()).isEqualTo(InventoryStatus.AVAILABLE);
        assertThat(repository.findById("SECOND").orElseThrow().getStatus()).isEqualTo(InventoryStatus.AVAILABLE);
        assertThat(historyRepository.count()).isZero();

        createFailingTrigger("inventory_items", "fail_item_update", "UPDATE");
        try {
            mockMvc.perform(patch("/api/v1/inventory/status")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(batch))
                    .andExpect(status().isInternalServerError());
        } finally {
            dropFailingTrigger("inventory_items", "fail_item_update");
        }
        assertThat(repository.findById("DRILL-001").orElseThrow().getStatus()).isEqualTo(InventoryStatus.AVAILABLE);
        assertThat(repository.findById("SECOND").orElseThrow().getStatus()).isEqualTo(InventoryStatus.AVAILABLE);
        assertThat(historyRepository.count()).isZero();
    }

    @Test
    void serializesConcurrentDedicatedTransitions() throws Exception {
        repository.saveAndFlush(new InventoryItem("DRILL-001", "Drill", "Original", InventoryStatus.AVAILABLE));
        repository.saveAndFlush(new InventoryItem("MIXER-001", "Mixer", "Original", InventoryStatus.AVAILABLE));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> results = List.of(InventoryStatus.RESERVED, InventoryStatus.RENTED).stream()
                    .map(target -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return mockMvc.perform(
                                        patch("/api/v1/inventory/status")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        target == InventoryStatus.RESERVED
                                                                ? "[{\"serialNumber\":\"DRILL-001\",\"status\":\"RESERVED\"},{\"serialNumber\":\"MIXER-001\",\"status\":\"RESERVED\"}]"
                                                                : "[{\"serialNumber\":\"MIXER-001\",\"status\":\"RENTED\"},{\"serialNumber\":\"DRILL-001\",\"status\":\"RENTED\"}]"))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    }))
                    .toList();

            ready.await();
            start.countDown();
            assertThat(List.of(results.get(0).get(), results.get(1).get()))
                    .allMatch(result -> result == 204 || result == 409)
                    .contains(204);
        }

        for (String serial : List.of("DRILL-001", "MIXER-001")) {
            List<InventoryStatusHistory> history = historyRepository.findAll(Sort.by("id")).stream()
                    .filter(record -> record.getSerialNumber().equals(serial))
                    .toList();
            assertThat(history).isNotEmpty();
            InventoryStatus expectedSource = InventoryStatus.AVAILABLE;
            for (InventoryStatusHistory record : history) {
                assertThat(record.getStatusFrom()).isEqualTo(expectedSource);
                assertThat(isPermittedTransition(record.getStatusFrom(), record.getStatusTo()))
                        .isTrue();
                expectedSource = record.getStatusTo();
            }
            assertThat(repository.findById(serial).orElseThrow().getStatus()).isEqualTo(expectedSource);
        }
        assertThat(repository.findById("DRILL-001").orElseThrow().getStatus())
                .isEqualTo(repository.findById("MIXER-001").orElseThrow().getStatus());
    }

    @Test
    void rejectsSerialMismatchAndInvalidReplacementsWithoutChangingTheOriginal() throws Exception {
        repository.saveAndFlush(new InventoryItem("DRILL-001", "Drill", "Original", InventoryStatus.RESERVED));

        assertInvalidReplacement(validRequest("drill-001", "Changed", "Changed", "AVAILABLE"));
        assertInvalidReplacement(validRequest("OTHER-001", "Changed", "Changed", "AVAILABLE"));
        assertInvalidReplacement(validRequest("DRILL-001", " ", "Changed", "AVAILABLE"));
        assertInvalidReplacement(validRequest("DRILL-001", "Changed", "Changed", "UNKNOWN"));
        assertInvalidReplacement("""
                {"serialNumber":"DRILL-001","type":"Changed","name":"Changed","status":"AVAILABLE","extra":true}
                """);
        assertInvalidReplacement("{");

        InventoryItem persisted = repository.findById("DRILL-001").orElseThrow();
        org.assertj.core.api.Assertions.assertThat(persisted.getType()).isEqualTo("Drill");
        org.assertj.core.api.Assertions.assertThat(persisted.getName()).isEqualTo("Original");
        org.assertj.core.api.Assertions.assertThat(persisted.getStatus()).isEqualTo(InventoryStatus.RESERVED);
        org.assertj.core.api.Assertions.assertThat(repository.count()).isOne();
    }

    @Test
    void doesNotUpsertWhenReplacingAMissingItem() throws Exception {
        mockMvc.perform(put("/api/v1/inventory/MISSING")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest("MISSING", "Drill", "Name", "AVAILABLE")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVENTORY_ITEM_NOT_FOUND"));

        org.assertj.core.api.Assertions.assertThat(repository.count()).isZero();
    }

    @Test
    void permanentlyDeletesItemsInEveryStatusAndRejectsMissingDeletes() throws Exception {
        for (InventoryStatus inventoryStatus : InventoryStatus.values()) {
            repository.saveAndFlush(new InventoryItem("DRILL-001", "Drill", "Name", inventoryStatus));

            mockMvc.perform(delete("/api/v1/inventory/DRILL-001"))
                    .andExpect(status().isNoContent())
                    .andExpect(content().string(""));
            mockMvc.perform(get("/api/v1/inventory/DRILL-001")).andExpect(status().isNotFound());
        }

        mockMvc.perform(delete("/api/v1/inventory/MISSING"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVENTORY_ITEM_NOT_FOUND"));
    }

    @Test
    void returnsStableConflictAndPreservesTheOriginal() throws Exception {
        mockMvc.perform(post("/api/v1/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(conflictRequest("Original")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(conflictRequest("Replacement")))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:rentflow:problem:inventory-item-already-exists"))
                .andExpect(jsonPath("$.code").value("INVENTORY_ITEM_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.detail").value("Inventory item 'DRILL-CONFLICT' already exists."));

        assertThat(repository.findById(SERIAL_NUMBER)).get().extracting("name").isEqualTo("Original");
    }

    @Test
    void concurrentCreateHasOneWinnerAndOneConflict() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> results = List.of("First", "Second").stream()
                    .map(name -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return mockMvc.perform(post("/api/v1/inventory")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(conflictRequest(name)))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    }))
                    .toList();

            ready.await();
            start.countDown();

            assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(201, 409);
        }

        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    void returnsTheDefaultHistoryPageWithOnlyPublicFields() throws Exception {
        insertHistory(
                "DRILL-001",
                InventoryStatus.AVAILABLE,
                InventoryStatus.RESERVED,
                Instant.parse("2026-08-04T10:00:00Z"));
        insertHistory(
                "DRILL-001", InventoryStatus.RESERVED, InventoryStatus.RENTED, Instant.parse("2026-08-04T11:00:00Z"));
        insertHistory(
                "MIXER-001",
                InventoryStatus.RENTED,
                InventoryStatus.INSPECTION_REQUIRED,
                Instant.parse("2026-08-04T12:00:00Z"));

        mockMvc.perform(get("/api/v1/inventory-history"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", aMapWithSize(2)))
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.content[0]", aMapWithSize(4)))
                .andExpect(jsonPath("$.content[0].serialNumber").value("MIXER-001"))
                .andExpect(jsonPath("$.content[0].statusFrom").value("RENTED"))
                .andExpect(jsonPath("$.content[0].statusTo").value("INSPECTION_REQUIRED"))
                .andExpect(jsonPath("$.content[0].timestamp").value("2026-08-04T12:00:00Z"))
                .andExpect(jsonPath("$.content[0].id").doesNotExist())
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.page.totalPages").value(1));
    }

    @Test
    void usesTheInternalIdentityAsTheSameDirectionSortTieBreaker() throws Exception {
        Instant timestamp = Instant.parse("2026-08-04T10:00:00Z");
        insertHistory("A-100", InventoryStatus.AVAILABLE, InventoryStatus.RESERVED, timestamp);
        insertHistory("B-200", InventoryStatus.AVAILABLE, InventoryStatus.RENTED, timestamp);

        mockMvc.perform(get("/api/v1/inventory-history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].serialNumber", contains("B-200", "A-100")));
        mockMvc.perform(get("/api/v1/inventory-history")
                        .param("sort", "timestamp")
                        .param("direction", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].serialNumber", contains("A-100", "B-200")));
    }

    @Test
    void paginatesAndAppliesCaseSensitiveLiteralSerialFiltering() throws Exception {
        insertHistory(
                "DRILL_001",
                InventoryStatus.AVAILABLE,
                InventoryStatus.RESERVED,
                Instant.parse("2026-08-04T10:00:00Z"));
        insertHistory(
                "DRILL-002", InventoryStatus.AVAILABLE, InventoryStatus.RENTED, Instant.parse("2026-08-04T11:00:00Z"));
        insertHistory(
                "drill-003", InventoryStatus.AVAILABLE, InventoryStatus.RETIRED, Instant.parse("2026-08-04T12:00:00Z"));

        mockMvc.perform(get("/api/v1/inventory-history")
                        .param("serialNumber", "DRILL")
                        .param("page", "1")
                        .param("size", "1")
                        .param("sort", "serialNumber")
                        .param("direction", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].serialNumber", contains("DRILL_001")))
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.page.totalPages").value(2));
        mockMvc.perform(get("/api/v1/inventory-history").param("serialNumber", "drill"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].serialNumber", contains("drill-003")));
        mockMvc.perform(get("/api/v1/inventory-history").param("serialNumber", "_"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].serialNumber", contains("DRILL_001")));
        mockMvc.perform(get("/api/v1/inventory-history").param("serialNumber", "%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", empty()));
        mockMvc.perform(get("/api/v1/inventory-history").param("serialNumber", "\\"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", empty()));
    }

    @ParameterizedTest
    @MethodSource("historySortCases")
    void sortsHistoryByEveryPublicFieldInBothDirections(String field, String direction, List<String> expectedSerials)
            throws Exception {
        seedHistoryForSorting();

        mockMvc.perform(get("/api/v1/inventory-history").param("sort", field).param("direction", direction))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].serialNumber", contains(expectedSerials.toArray())));
    }

    @ParameterizedTest
    @MethodSource("invalidHistoryQueries")
    void rejectsInvalidHistoryQueries(String query, String field) throws Exception {
        expectValidation(mockMvc.perform(get("/api/v1/inventory-history?" + query)), "/api/v1/inventory-history")
                .andExpect(jsonPath("$.violations[0].field").value(field));
    }

    @Test
    void keepsDeletedItemHistoryAndDoesNotShadowTheHistorySerialNumber() throws Exception {
        repository.saveAndFlush(new InventoryItem("DRILL-AUDIT", "Drill", "Audit", InventoryStatus.AVAILABLE));
        mockMvc.perform(patch("/api/v1/inventory/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusUpdateRequest("DRILL-AUDIT", InventoryStatus.RESERVED)))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/inventory/DRILL-AUDIT")).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/inventory-history").param("serialNumber", "DRILL-AUDIT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].serialNumber", contains("DRILL-AUDIT")));

        repository.saveAndFlush(new InventoryItem("history", "Drill", "Route check", InventoryStatus.AVAILABLE));
        mockMvc.perform(get("/api/v1/inventory/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serialNumber").value("history"));
        mockMvc.perform(get("/api/v1/inventory-history")).andExpect(status().isOk());
    }

    @Test
    void returnsDefaultPageWithDeterministicOrderWithoutCredentials() throws Exception {
        seedInventory();

        mockMvc.perform(get("/api/v1/inventory"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", aMapWithSize(2)))
                .andExpect(jsonPath("$.content[*].serialNumber", contains("A-100", "B-200", "C-300", "D-400")))
                .andExpect(jsonPath("$.page", aMapWithSize(4)))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.totalElements").value(4))
                .andExpect(jsonPath("$.page.totalPages").value(1));
    }

    @Test
    void supportsBoundedPagesAndPagesPastTheEnd() throws Exception {
        seedInventory();

        mockMvc.perform(get("/api/v1/inventory").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].serialNumber", contains("C-300", "D-400")))
                .andExpect(jsonPath("$.page.number").value(1))
                .andExpect(jsonPath("$.page.size").value(2))
                .andExpect(jsonPath("$.page.totalElements").value(4))
                .andExpect(jsonPath("$.page.totalPages").value(2));

        mockMvc.perform(get("/api/v1/inventory").param("page", "5").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", empty()))
                .andExpect(jsonPath("$.page.number").value(5))
                .andExpect(jsonPath("$.page.size").value(2))
                .andExpect(jsonPath("$.page.totalElements").value(4))
                .andExpect(jsonPath("$.page.totalPages").value(2));
    }

    @Test
    void returnsAnEmptyPageForAnEmptyCatalogue() throws Exception {
        seedInventory();
        repository.deleteAllInBatch();

        mockMvc.perform(get("/api/v1/inventory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", empty()))
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$.page.totalPages").value(0));
    }

    @Test
    void appliesExactStatusAndCaseInsensitiveExactTypeFilters() throws Exception {
        seedInventory();

        mockMvc.perform(get("/api/v1/inventory").param("status", "AVAILABLE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].serialNumber", contains("A-100", "C-300")));

        mockMvc.perform(get("/api/v1/inventory").param("type", " drill "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].serialNumber", contains("A-100", "C-300")))
                .andExpect(jsonPath("$.content[0].type").value("Drill"));

        mockMvc.perform(get("/api/v1/inventory").param("type", "rill"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", empty()));

        mockMvc.perform(get("/api/v1/inventory").param("status", "RENTED").param("type", " mixer "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].serialNumber", contains("B-200")));

        mockMvc.perform(get("/api/v1/inventory").param("status", "RETIRED").param("type", "Drill"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", empty()))
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @ParameterizedTest
    @MethodSource("sortCases")
    void supportsEveryAllowlistedSortInBothDirections(String field, String direction, List<String> expectedSerials)
            throws Exception {
        seedInventory();

        mockMvc.perform(get("/api/v1/inventory").param("sort", field).param("direction", direction))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].serialNumber", contains(expectedSerials.toArray())));
    }

    @ParameterizedTest
    @MethodSource("invalidQueries")
    void rejectsInvalidOrUnsupportedQueryParameters(String query) throws Exception {
        seedInventory();

        mockMvc.perform(get("/api/v1/inventory?" + query))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field").isNotEmpty());
    }

    @Test
    void rejectsBlankTypeFilter() throws Exception {
        seedInventory();

        mockMvc.perform(get("/api/v1/inventory").param("type", "  "))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field").value("type"));
    }

    @Test
    void returnsExactSortedViolationsForBodyValidation() throws Exception {
        expectValidation(
                        mockMvc.perform(post("/api/v1/inventory")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}")),
                        "/api/v1/inventory")
                .andExpect(jsonPath("$.violations", hasSize(4)))
                .andExpect(jsonPath("$.violations[0].field").value("name"))
                .andExpect(jsonPath("$.violations[0].message").value("must not be blank"))
                .andExpect(jsonPath("$.violations[1].field").value("serialNumber"))
                .andExpect(jsonPath("$.violations[1].message").value("must not be blank"))
                .andExpect(jsonPath("$.violations[2].field").value("status"))
                .andExpect(jsonPath("$.violations[2].message").value("must not be null"))
                .andExpect(jsonPath("$.violations[3].field").value("type"))
                .andExpect(jsonPath("$.violations[3].message").value("must not be blank"));
    }

    @Test
    void returnsExactViolationsForPathAndQueryValidation() throws Exception {
        expectValidation(mockMvc.perform(get(URI.create("/api/v1/inventory/%20bad%20"))), "/api/v1/inventory/%20bad%20")
                .andExpect(jsonPath("$.violations", hasSize(1)))
                .andExpect(jsonPath("$.violations[0].field").value("serialNumber"));
        expectValidation(mockMvc.perform(get("/api/v1/inventory").param("page", "not-a-number")), "/api/v1/inventory")
                .andExpect(jsonPath("$.violations[0].field").value("page"))
                .andExpect(jsonPath("$.violations[0].message").value("must have a valid value"));
        expectValidation(mockMvc.perform(get("/api/v1/inventory").param("status", "UNKNOWN")), "/api/v1/inventory")
                .andExpect(jsonPath("$.violations[0].field").value("status"));
        expectValidation(mockMvc.perform(get("/api/v1/inventory").param("unexpected", "value")), "/api/v1/inventory")
                .andExpect(jsonPath("$.violations[0].field").value("unexpected"))
                .andExpect(jsonPath("$.violations[0].message").value("is not supported"));
        expectValidation(mockMvc.perform(get("/api/v1/inventory").param("sort", "name", "status")), "/api/v1/inventory")
                .andExpect(jsonPath("$.violations[0].field").value("sort"))
                .andExpect(jsonPath("$.violations[0].message").value("must be supplied exactly once"));
        expectValidation(mockMvc.perform(get("/api/v1/inventory").param("type", "  ")), "/api/v1/inventory")
                .andExpect(jsonPath("$.violations[0].field").value("type"))
                .andExpect(jsonPath("$.violations[0].message").value("must not be blank"));
    }

    @Test
    void distinguishesInvalidEnumsFromUnreadableJson() throws Exception {
        expectValidation(
                        mockMvc.perform(post("/api/v1/inventory")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validationRequest("DRILL-001", "UNKNOWN"))),
                        "/api/v1/inventory")
                .andExpect(jsonPath("$.violations", hasSize(1)))
                .andExpect(jsonPath("$.violations[0].field").value("status"))
                .andExpect(jsonPath("$.violations[0].message").value("must be a defined inventory status"));

        expectProblem(
                mockMvc.perform(post("/api/v1/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{")),
                HttpStatus.BAD_REQUEST,
                "urn:rentflow:problem:malformed-json",
                "Malformed JSON",
                "The request body could not be read.",
                "/api/v1/inventory",
                "MALFORMED_JSON");
        expectProblem(
                mockMvc.perform(post("/api/v1/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "serialNumber": "DRILL-001",
                                  "type": "Drill",
                                  "name": "Name",
                                  "status": "AVAILABLE",
                                  "unknown": true
                                }
                                """)),
                HttpStatus.BAD_REQUEST,
                "urn:rentflow:problem:malformed-json",
                "Malformed JSON",
                "The request body could not be read.",
                "/api/v1/inventory",
                "MALFORMED_JSON");
    }

    @Test
    void mapsMediaNegotiationAndUnsupportedMethods() throws Exception {
        expectProblem(
                mockMvc.perform(post("/api/v1/inventory")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("not-json")),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "urn:rentflow:problem:unsupported-media-type",
                "Unsupported media type",
                "The request media type is not supported.",
                "/api/v1/inventory",
                "UNSUPPORTED_MEDIA_TYPE");

        expectProblem(
                mockMvc.perform(get("/api/v1/inventory").accept(MediaType.TEXT_PLAIN)),
                HttpStatus.NOT_ACCEPTABLE,
                "urn:rentflow:problem:not-acceptable",
                "Not acceptable",
                "No acceptable response representation is available.",
                "/api/v1/inventory",
                "NOT_ACCEPTABLE");

        MvcResult patchResult = expectProblem(
                        mockMvc.perform(patch("/api/v1/inventory/DRILL-001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validationRequest("DRILL-001", "AVAILABLE"))),
                        HttpStatus.METHOD_NOT_ALLOWED,
                        "urn:rentflow:problem:method-not-allowed",
                        "Method not allowed",
                        "The HTTP method is not supported for this resource.",
                        "/api/v1/inventory/DRILL-001",
                        "METHOD_NOT_ALLOWED")
                .andReturn();
        assertThat(patchResult.getResponse().getHeader(HttpHeaders.ALLOW))
                .contains("GET")
                .contains("PUT")
                .contains("DELETE");

        expectProblem(
                mockMvc.perform(post("/api/v1/inventory/DRILL-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validationRequest("DRILL-001", "AVAILABLE"))),
                HttpStatus.METHOD_NOT_ALLOWED,
                "urn:rentflow:problem:method-not-allowed",
                "Method not allowed",
                "The HTTP method is not supported for this resource.",
                "/api/v1/inventory/DRILL-001",
                "METHOD_NOT_ALLOWED");
    }

    @Test
    void mapsStableInventoryAndUnmappedResourceProblems() throws Exception {
        expectProblem(
                mockMvc.perform(get("/api/v1/inventory/MISSING")),
                HttpStatus.NOT_FOUND,
                "urn:rentflow:problem:inventory-item-not-found",
                "Inventory item not found",
                "Inventory item 'MISSING' was not found.",
                "/api/v1/inventory/MISSING",
                "INVENTORY_ITEM_NOT_FOUND");

        repository.saveAndFlush(new InventoryItem("DRILL-001", "Drill", "Original", InventoryStatus.AVAILABLE));
        expectProblem(
                mockMvc.perform(post("/api/v1/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validationRequest("DRILL-001", "RETIRED"))),
                HttpStatus.CONFLICT,
                "urn:rentflow:problem:inventory-item-already-exists",
                "Inventory item already exists",
                "Inventory item 'DRILL-001' already exists.",
                "/api/v1/inventory",
                "INVENTORY_ITEM_ALREADY_EXISTS");
        assertThat(repository.findById("DRILL-001").orElseThrow().getStatus()).isEqualTo(InventoryStatus.AVAILABLE);

        expectResourceNotFound("/api/v1/not-a-resource");
    }

    @Test
    void everyInventoryOperationIsReachableWithoutCredentials() throws Exception {
        mockMvc.perform(post("/api/v1/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validationRequest("NOAUTH-001", "AVAILABLE")))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/inventory")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/inventory-history")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/inventory/NOAUTH-001")).andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/inventory/NOAUTH-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validationRequest("NOAUTH-001", "RENTED")))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/inventory/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusUpdateRequest("NOAUTH-001", InventoryStatus.INSPECTION_REQUIRED)))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/inventory/NOAUTH-001")).andExpect(status().isNoContent());
    }

    @Test
    void authenticationAndAuthorizationCapabilitiesRemainUnmapped() throws Exception {
        for (String path :
                new String[] {"/login", "/api/v1/token", "/api/v1/users", "/api/v1/roles", "/api/v1/permissions"}) {
            expectResourceNotFound(path);
        }
    }

    private void assertInvalidCreate(String body) throws Exception {
        mockMvc.perform(post("/api/v1/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").isNotEmpty())
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.instance").value("/api/v1/inventory"))
                .andExpect(jsonPath("$.code").isNotEmpty());
    }

    private void assertInvalidReplacement(String body) throws Exception {
        mockMvc.perform(put("/api/v1/inventory/DRILL-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").isNotEmpty());
    }

    private String conflictRequest(String name) {
        return """
                {
                  "serialNumber": "DRILL-CONFLICT",
                  "type": "Drill",
                  "name": "%s",
                  "status": "AVAILABLE"
                }
                """.formatted(name);
    }

    private void seedInventory() {
        repository.saveAllAndFlush(List.of(
                item("A-100", "Drill", "Alpha", InventoryStatus.AVAILABLE),
                item("B-200", "Mixer", "Beta", InventoryStatus.RENTED),
                item("C-300", "Drill", "Alpha", InventoryStatus.AVAILABLE),
                item("D-400", "Jackhammer", "Delta", InventoryStatus.RESERVED)));
    }

    private void seedHistoryForSorting() {
        insertHistory(
                "C-300",
                InventoryStatus.RENTED,
                InventoryStatus.INSPECTION_REQUIRED,
                Instant.parse("2026-08-04T10:00:00Z"));
        insertHistory(
                "D-400",
                InventoryStatus.INSPECTION_REQUIRED,
                InventoryStatus.RETIRED,
                Instant.parse("2026-08-04T11:00:00Z"));
        insertHistory(
                "B-200", InventoryStatus.RESERVED, InventoryStatus.AVAILABLE, Instant.parse("2026-08-04T12:00:00Z"));
        insertHistory(
                "A-100", InventoryStatus.AVAILABLE, InventoryStatus.RENTED, Instant.parse("2026-08-04T13:00:00Z"));
    }

    private void insertHistory(
            String serialNumber, InventoryStatus statusFrom, InventoryStatus statusTo, Instant timestamp) {
        jdbcTemplate.update("""
                        INSERT INTO inventory.inventory_status_history
                            (serial_number, status_from, status_to, transitioned_at)
                        VALUES (?, ?, ?, ?)
                        """, serialNumber, statusFrom.name(), statusTo.name(), Timestamp.from(timestamp));
    }

    private static Stream<Arguments> sortCases() {
        return Stream.of(
                Arguments.of("serialNumber", "asc", List.of("A-100", "B-200", "C-300", "D-400")),
                Arguments.of("serialNumber", "DESC", List.of("D-400", "C-300", "B-200", "A-100")),
                Arguments.of("type", "asc", List.of("A-100", "C-300", "D-400", "B-200")),
                Arguments.of("type", "desc", List.of("B-200", "D-400", "A-100", "C-300")),
                Arguments.of("name", "asc", List.of("A-100", "C-300", "B-200", "D-400")),
                Arguments.of("name", "desc", List.of("D-400", "B-200", "A-100", "C-300")),
                Arguments.of("status", "asc", List.of("A-100", "C-300", "B-200", "D-400")),
                Arguments.of("status", "desc", List.of("D-400", "B-200", "A-100", "C-300")));
    }

    private static Stream<Arguments> transitionCases() {
        Stream.Builder<Arguments> cases = Stream.builder();
        for (InventoryStatus source : InventoryStatus.values()) {
            for (InventoryStatus target : InventoryStatus.values()) {
                cases.add(Arguments.of(source, target, isPermittedTransition(source, target)));
            }
        }
        return cases.build();
    }

    private static boolean isPermittedTransition(InventoryStatus source, InventoryStatus target) {
        if (source == target) {
            return false;
        }
        return switch (source) {
            case AVAILABLE -> true;
            case RESERVED -> target == InventoryStatus.AVAILABLE || target == InventoryStatus.RENTED;
            case RENTED -> target == InventoryStatus.INSPECTION_REQUIRED;
            case INSPECTION_REQUIRED ->
                target == InventoryStatus.AVAILABLE
                        || target == InventoryStatus.UNDER_MAINTENANCE
                        || target == InventoryStatus.RETIRED;
            case UNDER_MAINTENANCE -> target == InventoryStatus.AVAILABLE || target == InventoryStatus.RETIRED;
            case RETIRED -> false;
        };
    }

    private static Stream<Arguments> historySortCases() {
        return Stream.of(
                Arguments.of("serialNumber", "asc", List.of("A-100", "B-200", "C-300", "D-400")),
                Arguments.of("serialNumber", "DESC", List.of("D-400", "C-300", "B-200", "A-100")),
                Arguments.of("statusFrom", "asc", List.of("A-100", "D-400", "C-300", "B-200")),
                Arguments.of("statusFrom", "desc", List.of("B-200", "C-300", "D-400", "A-100")),
                Arguments.of("statusTo", "asc", List.of("B-200", "C-300", "A-100", "D-400")),
                Arguments.of("statusTo", "desc", List.of("D-400", "A-100", "C-300", "B-200")),
                Arguments.of("timestamp", "asc", List.of("C-300", "D-400", "B-200", "A-100")),
                Arguments.of("timestamp", "desc", List.of("A-100", "B-200", "D-400", "C-300")));
    }

    private static Stream<Arguments> invalidHistoryQueries() {
        return Stream.of(
                Arguments.of("page=-1", "page"),
                Arguments.of("page=not-a-number", "page"),
                Arguments.of("size=0", "size"),
                Arguments.of("size=101", "size"),
                Arguments.of("size=not-a-number", "size"),
                Arguments.of("serialNumber=", "serialNumber"),
                Arguments.of("serialNumber=" + "S".repeat(65), "serialNumber"),
                Arguments.of("sort=unknown", "sort"),
                Arguments.of("direction=sideways", "direction"),
                Arguments.of("unknown=value", "unknown"),
                Arguments.of("page=0&page=1", "page"));
    }

    private static Stream<String> invalidQueries() {
        return Stream.of(
                "page=-1",
                "page=not-a-number",
                "size=0",
                "size=101",
                "status=available",
                "status=",
                "type=" + "T".repeat(101),
                "sort=unknown",
                "direction=sideways",
                "unknown=value",
                "page=0&page=1");
    }

    private InventoryItem item(String serial, String type, String name, InventoryStatus status) {
        return new InventoryItem(serial, type, name, status);
    }

    private ResultActions expectValidation(ResultActions actions, String instance) throws Exception {
        return actions.andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$", aMapWithSize(7)))
                .andExpect(jsonPath("$.type").value("urn:rentflow:problem:validation-failed"))
                .andExpect(jsonPath("$.title").value("Request validation failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("One or more request values are invalid."))
                .andExpect(jsonPath("$.instance").value(instance))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations").isNotEmpty());
    }

    private ResultActions expectProblem(
            ResultActions actions,
            HttpStatus status,
            String type,
            String title,
            String detail,
            String instance,
            String code)
            throws Exception {
        return actions.andExpect(status().is(status.value()))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$", aMapWithSize(6)))
                .andExpect(jsonPath("$.type").value(type))
                .andExpect(jsonPath("$.title").value(title))
                .andExpect(jsonPath("$.status").value(status.value()))
                .andExpect(jsonPath("$.detail").value(detail))
                .andExpect(jsonPath("$.instance").value(instance))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.violations").doesNotExist());
    }

    private void expectResourceNotFound(String path) throws Exception {
        expectProblem(
                mockMvc.perform(get(path)),
                HttpStatus.NOT_FOUND,
                "urn:rentflow:problem:resource-not-found",
                "Resource not found",
                "The requested resource was not found.",
                path,
                "RESOURCE_NOT_FOUND");
    }

    private String validationRequest(String serialNumber, String status) {
        return """
                {
                  "serialNumber": "%s",
                  "type": "Industrial drill",
                  "name": "Bosch GBH",
                  "status": "%s"
                }
                """.formatted(serialNumber, status);
    }

    private String statusUpdateRequest(String serialNumber, InventoryStatus status) {
        return "[{\"serialNumber\":\"%s\",\"status\":\"%s\"}]".formatted(serialNumber, status);
    }

    private void createFailingTrigger(String table, String trigger, String event) {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION inventory.%1$s_function()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    IF NEW.serial_number = 'SECOND' THEN
                        RAISE EXCEPTION 'forced test failure';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """.formatted(trigger));
        jdbcTemplate.execute("""
                CREATE TRIGGER %1$s
                BEFORE %2$s ON inventory.%3$s
                FOR EACH ROW
                EXECUTE FUNCTION inventory.%1$s_function()
                """.formatted(trigger, event, table));
    }

    private void dropFailingTrigger(String table, String trigger) {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + trigger + " ON inventory." + table);
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS inventory." + trigger + "_function()");
    }

    private String validRequest(String serialNumber, String type, String name) {
        return validRequest(serialNumber, type, name, "AVAILABLE");
    }

    private String validRequest(String serialNumber, String type, String name, String status) {
        return """
                {
                  "serialNumber": "%s",
                  "type": "%s",
                  "name": "%s",
                  "status": "%s"
                }
                """.formatted(serialNumber, type, name, status);
    }
}
