package com.rentflow.dto;

import com.rentflow.model.InventoryStatus;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Persisted inventory item.", example = """
                {
                  "serialNumber": "DRILL-001",
                  "type": "Industrial drill",
                  "name": "Bosch GBH 8-45 DV",
                  "status": "AVAILABLE"
                }
                """)
public record InventoryItemResponse(
        @Schema(
                description = "Unique, manually assigned, immutable serial number.",
                example = "DRILL-001",
                minLength = 1,
                maxLength = 64,
                pattern = InventoryItemRequest.SERIAL_NUMBER_PATTERN,
                requiredMode = Schema.RequiredMode.REQUIRED)
        String serialNumber,

        @Schema(
                description = "Equipment category or type.",
                example = "Industrial drill",
                minLength = 1,
                maxLength = 100,
                requiredMode = Schema.RequiredMode.REQUIRED)
        String type,

        @Schema(
                description = "Human-readable equipment name.",
                example = "Bosch GBH 8-45 DV",
                minLength = 1,
                maxLength = 200,
                requiredMode = Schema.RequiredMode.REQUIRED)
        String name,

        @Schema(
                description = "Current inventory status.",
                example = "AVAILABLE",
                allowableValues = {
                    "AVAILABLE",
                    "RESERVED",
                    "RENTED",
                    "INSPECTION_REQUIRED",
                    "UNDER_MAINTENANCE",
                    "RETIRED"
                },
                requiredMode = Schema.RequiredMode.REQUIRED)
        InventoryStatus status) {}
