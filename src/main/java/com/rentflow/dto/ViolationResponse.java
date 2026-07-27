package com.rentflow.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One rejected request field and its validation message.")
public record ViolationResponse(
        @Schema(description = "Rejected request field.", example = "name", requiredMode = Schema.RequiredMode.REQUIRED)
        String field,

        @Schema(
                description = "Stable validation message.",
                example = "must not be blank",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String message) {}
