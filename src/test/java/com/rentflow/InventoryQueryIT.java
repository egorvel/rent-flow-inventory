package com.rentflow;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rentflow.model.InventoryItem;
import com.rentflow.model.InventoryStatus;
import com.rentflow.repository.InventoryRepository;
import com.rentflow.support.PostgresIntegrationTest;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InventoryQueryIT extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InventoryRepository repository;

    @BeforeEach
    void seedInventory() {
        repository.deleteAllInBatch();
        repository.saveAllAndFlush(List.of(
                item("A-100", "Drill", "Alpha", InventoryStatus.AVAILABLE),
                item("B-200", "Mixer", "Beta", InventoryStatus.RENTED),
                item("C-300", "Drill", "Alpha", InventoryStatus.AVAILABLE),
                item("D-400", "Jackhammer", "Delta", InventoryStatus.RESERVED)));
    }

    @Test
    void returnsDefaultPageWithDeterministicOrderWithoutCredentials() throws Exception {
        mockMvc.perform(get("/api/v1/inventory"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.items[*].serialNumber", contains("A-100", "B-200", "C-300", "D-400")))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void supportsBoundedPagesAndPagesPastTheEnd() throws Exception {
        mockMvc.perform(get("/api/v1/inventory").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].serialNumber", contains("C-300", "D-400")))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(2));

        mockMvc.perform(get("/api/v1/inventory").param("page", "5").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", empty()))
                .andExpect(jsonPath("$.page").value(5))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void returnsAnEmptyPageForAnEmptyCatalogue() throws Exception {
        repository.deleteAllInBatch();

        mockMvc.perform(get("/api/v1/inventory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", empty()))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void appliesExactStatusAndCaseInsensitiveExactTypeFilters() throws Exception {
        mockMvc.perform(get("/api/v1/inventory").param("status", "AVAILABLE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].serialNumber", contains("A-100", "C-300")));

        mockMvc.perform(get("/api/v1/inventory").param("type", " drill "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].serialNumber", contains("A-100", "C-300")))
                .andExpect(jsonPath("$.items[0].type").value("Drill"));

        mockMvc.perform(get("/api/v1/inventory").param("type", "rill"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", empty()));

        mockMvc.perform(get("/api/v1/inventory").param("status", "RENTED").param("type", " mixer "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].serialNumber", contains("B-200")));

        mockMvc.perform(get("/api/v1/inventory").param("status", "RETIRED").param("type", "Drill"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", empty()))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @ParameterizedTest
    @MethodSource("sortCases")
    void supportsEveryAllowlistedSortInBothDirections(String field, String direction, List<String> expectedSerials)
            throws Exception {
        mockMvc.perform(get("/api/v1/inventory").param("sort", field).param("direction", direction))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].serialNumber", contains(expectedSerials.toArray())));
    }

    @ParameterizedTest
    @MethodSource("invalidQueries")
    void rejectsInvalidOrUnsupportedQueryParameters(String query) throws Exception {
        mockMvc.perform(get("/api/v1/inventory?" + query))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field").isNotEmpty());
    }

    @Test
    void rejectsBlankTypeFilter() throws Exception {
        mockMvc.perform(get("/api/v1/inventory").param("type", "  "))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field").value("type"));
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
}
