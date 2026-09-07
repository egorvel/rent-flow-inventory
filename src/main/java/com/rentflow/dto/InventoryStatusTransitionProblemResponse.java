package com.rentflow.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "RFC 9457 Problem Details for an atomic batch rejected by lifecycle validation.")
public record InventoryStatusTransitionProblemResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String type,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String detail,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String instance,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,

        @Schema(
                description = "All and only failed entries in request order.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<InventoryStatusTransitionFailureDTO> failedItems) {}
