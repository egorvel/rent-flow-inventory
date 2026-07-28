package com.rentflow;

import java.net.URI;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.rentflow.model.InventoryItem;
import com.rentflow.model.InventoryStatus;
import com.rentflow.repository.InventoryRepository;
import com.rentflow.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InventoryValidationIT extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InventoryRepository repository;

    @BeforeEach
    void clearInventory() {
        repository.deleteAllInBatch();
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
                                .content(validRequest("DRILL-001", "UNKNOWN"))),
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

        var patchResult = expectProblem(
                        mockMvc.perform(patch("/api/v1/inventory/DRILL-001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validRequest("DRILL-001", "AVAILABLE"))),
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
                        .content(validRequest("DRILL-001", "AVAILABLE"))),
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
                        .content(validRequest("DRILL-001", "RETIRED"))),
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
                        .content(validRequest("NOAUTH-001", "AVAILABLE")))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/inventory")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/inventory/NOAUTH-001")).andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/inventory/NOAUTH-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest("NOAUTH-001", "RENTED")))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/inventory/NOAUTH-001")).andExpect(status().isNoContent());
    }

    @Test
    void authenticationAndAuthorizationCapabilitiesRemainUnmapped() throws Exception {
        for (var path :
                new String[] {"/login", "/api/v1/token", "/api/v1/users", "/api/v1/roles", "/api/v1/permissions"}) {
            expectResourceNotFound(path);
        }
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

    private String validRequest(String serialNumber, String status) {
        return """
                {
                  "serialNumber": "%s",
                  "type": "Industrial drill",
                  "name": "Bosch GBH",
                  "status": "%s"
                }
                """.formatted(serialNumber, status);
    }
}
