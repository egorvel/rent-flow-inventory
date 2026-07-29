package com.rentflow;

import java.net.URI;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.rentflow.model.InventoryItem;
import com.rentflow.model.InventoryStatus;
import com.rentflow.repository.InventoryRepository;
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

    @BeforeEach
    void clearInventory() {
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
        mockMvc.perform(get("/api/v1/inventory/NOAUTH-001")).andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/inventory/NOAUTH-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validationRequest("NOAUTH-001", "RENTED")))
                .andExpect(status().isOk());
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
