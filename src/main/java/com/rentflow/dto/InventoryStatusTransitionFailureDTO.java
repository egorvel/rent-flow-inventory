package com.rentflow.dto;

import com.rentflow.model.InventoryStatus;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "An entry that could not transition; ordered by original request index.")
public record InventoryStatusTransitionFailureDTO(
        @Schema(description = "Zero-based request index.", minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        int index,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String serialNumber,

        @Schema(description = "Requested target status.", requiredMode = Schema.RequiredMode.REQUIRED)
        InventoryStatus status,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"INVENTORY_ITEM_NOT_FOUND", "INVALID_INVENTORY_STATUS_TRANSITION"})
        String code,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String message) {}
