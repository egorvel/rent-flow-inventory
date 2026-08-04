package com.rentflow.dto;

import java.time.Instant;
import jakarta.validation.constraints.NotNull;

import com.rentflow.model.InventoryStatus;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A retained inventory status transition.", example = """
        {
          "serialNumber": "DRILL-001",
          "statusFrom": "RESERVED",
          "statusTo": "RENTED",
          "timestamp": "2026-08-04T19:10:30.123456Z"
        }
        """)
public record InventoryStatusHistoryDTO(
        @Schema(description = "Case-sensitive inventory serial number.", example = "DRILL-001") @NotNull String serialNumber,

        @Schema(description = "Status before the transition.", example = "RESERVED") @NotNull InventoryStatus statusFrom,

        @Schema(description = "Status after the transition.", example = "RENTED") @NotNull InventoryStatus statusTo,

        @Schema(
                description = "Database-authored transition time.",
                example = "2026-08-04T19:10:30.123456Z",
                type = "string",
                format = "date-time")
        @NotNull Instant timestamp) {}
