package com.rentflow.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Bounded page of inventory items.", example = """
                {
                  "items": [
                    {
                      "serialNumber": "DRILL-001",
                      "type": "Industrial drill",
                      "name": "Bosch GBH 8-45 DV",
                      "status": "AVAILABLE"
                    }
                  ],
                  "page": 0,
                  "size": 20,
                  "totalElements": 1,
                  "totalPages": 1
                }
                """)
public record InventoryPageResponse(
        @Schema(description = "Items in the requested page.", requiredMode = Schema.RequiredMode.REQUIRED)
        List<InventoryItemDTO> items,

        @Schema(
                description = "Zero-based requested page number.",
                example = "0",
                minimum = "0",
                requiredMode = Schema.RequiredMode.REQUIRED)
        int page,

        @Schema(
                description = "Requested maximum page size.",
                example = "20",
                minimum = "1",
                maximum = "100",
                requiredMode = Schema.RequiredMode.REQUIRED)
        int size,

        @Schema(
                description = "Number of matching items before page slicing.",
                example = "1",
                minimum = "0",
                requiredMode = Schema.RequiredMode.REQUIRED)
        long totalElements,

        @Schema(
                description = "Number of pages in the filtered result.",
                example = "1",
                minimum = "0",
                requiredMode = Schema.RequiredMode.REQUIRED)
        int totalPages) {}
