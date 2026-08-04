package com.rentflow.dto;

import jakarta.validation.constraints.NotNull;

import com.rentflow.model.InventoryStatus;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Requested inventory status transition.", example = """
                {
                  "status": "RENTED"
                }
                """)
public record InventoryStatusUpdateDTO(
        @Schema(
                description = "Target inventory status.",
                example = "RENTED",
                allowableValues = {
                    "AVAILABLE",
                    "RESERVED",
                    "RENTED",
                    "INSPECTION_REQUIRED",
                    "UNDER_MAINTENANCE",
                    "RETIRED"
                },
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "must not be null") InventoryStatus status) {}
