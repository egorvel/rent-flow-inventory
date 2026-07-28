package com.rentflow;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import com.rentflow.repository.InventoryRepository;
import com.rentflow.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InventoryConflictIT extends PostgresIntegrationTest {

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
    void returnsStableConflictAndPreservesTheOriginal() throws Exception {
        mockMvc.perform(post("/api/v1/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("Original")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("Replacement")))
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
                                        .content(request(name)))
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

    private String request(String name) {
        return """
                {
                  "serialNumber": "DRILL-CONFLICT",
                  "type": "Drill",
                  "name": "%s",
                  "status": "AVAILABLE"
                }
                """.formatted(name);
    }
}
