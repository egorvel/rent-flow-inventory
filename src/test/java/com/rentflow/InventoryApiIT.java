package com.rentflow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rentflow.model.InventoryItem;
import com.rentflow.model.InventoryStatus;
import com.rentflow.repository.InventoryRepository;
import com.rentflow.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InventoryApiIT extends PostgresIntegrationTest {

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

        var persisted = repository.findById("DRILL-001").orElseThrow();
        org.assertj.core.api.Assertions.assertThat(persisted.getSerialNumber()).isEqualTo("DRILL-001");
        org.assertj.core.api.Assertions.assertThat(persisted.getType()).isEqualTo("Industrial drill");
        org.assertj.core.api.Assertions.assertThat(persisted.getName()).isEqualTo("Updated drill");
        org.assertj.core.api.Assertions.assertThat(persisted.getStatus()).isEqualTo(InventoryStatus.UNDER_MAINTENANCE);
    }

    @Test
    void acceptsEveryDefinedStatusFromEveryPriorStatus() throws Exception {
        for (var priorStatus : InventoryStatus.values()) {
            for (var replacementStatus : InventoryStatus.values()) {
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

        var persisted = repository.findById("DRILL-001").orElseThrow();
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
        for (var inventoryStatus : InventoryStatus.values()) {
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
