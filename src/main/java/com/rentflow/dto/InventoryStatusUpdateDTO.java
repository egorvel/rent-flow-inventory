package com.rentflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import com.rentflow.model.InventoryStatus;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Requested inventory status transition.", example = """
                {
                  "serialNumber": "DRILL-001",
                  "status": "RENTED"
                }
                """)
public record InventoryStatusUpdateDTO(
        @Schema(
                description = "Case-sensitive inventory serial number.",
                example = "DRILL-001",
                minLength = 1,
                maxLength = 64,
                pattern = InventoryItemDTO.SERIAL_NUMBER_PATTERN,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "must not be blank") @Pattern(regexp = InventoryItemDTO.SERIAL_NUMBER_PATTERN, message = "must be a valid serial number") String serialNumber,

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
