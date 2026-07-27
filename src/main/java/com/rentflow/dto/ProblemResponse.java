package com.rentflow.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "RFC 9457 Problem Details with stable RentFlow extensions.", example = """
                {
                  "type": "urn:rentflow:problem:validation-failed",
                  "title": "Request validation failed",
                  "status": 400,
                  "detail": "One or more request values are invalid.",
                  "instance": "/api/v1/inventory",
                  "code": "VALIDATION_FAILED",
                  "violations": [
                    {
                      "field": "name",
                      "message": "must not be blank"
                    }
                  ]
                }
                """)
public record ProblemResponse(
        @Schema(
                description = "Stable problem type URI.",
                example = "urn:rentflow:problem:validation-failed",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String type,

        @Schema(
                description = "Stable human-readable problem title.",
                example = "Request validation failed",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String title,

        @Schema(
                description = "HTTP status code.",
                example = "400",
                minimum = "400",
                maximum = "599",
                requiredMode = Schema.RequiredMode.REQUIRED)
        int status,

        @Schema(
                description = "Safe problem explanation.",
                example = "One or more request values are invalid.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String detail,

        @Schema(
                description = "Request path without host information.",
                example = "/api/v1/inventory",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String instance,

        @Schema(
                description = "Stable machine-readable RentFlow problem code.",
                example = "VALIDATION_FAILED",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String code,

        @Schema(description = "Sorted field violations, present only for validation failures.")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<ViolationResponse> violations) {}
