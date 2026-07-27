package com.rentflow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

    private String validRequest(String serialNumber, String type, String name) {
        return """
                {
                  "serialNumber": "%s",
                  "type": "%s",
                  "name": "%s",
                  "status": "AVAILABLE"
                }
                """.formatted(serialNumber, type, name);
    }
}
