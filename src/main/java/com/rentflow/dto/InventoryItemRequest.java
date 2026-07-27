package com.rentflow.dto;

import com.rentflow.model.InventoryStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Complete inventory item representation used for creation and replacement.", example = """
                {
                  "serialNumber": "DRILL-001",
                  "type": "Industrial drill",
                  "name": "Bosch GBH 8-45 DV",
                  "status": "AVAILABLE"
                }
                """)
public record InventoryItemRequest(
        @Schema(
                description = "Unique, manually assigned, immutable serial number.",
                example = "DRILL-001",
                minLength = 1,
                maxLength = 64,
                pattern = SERIAL_NUMBER_PATTERN,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "must not be blank") @Pattern(
                regexp = SERIAL_NUMBER_PATTERN,
                message =
                        "must start with an alphanumeric character and contain only alphanumeric characters, dots, underscores, or hyphens")
        String serialNumber,

        @Schema(
                description = "Equipment category or type.",
                example = "Industrial drill",
                minLength = 1,
                maxLength = 100,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "must not be blank") @Size(min = 1, max = 100, message = "must contain between 1 and 100 characters") String type,

        @Schema(
                description = "Human-readable equipment name.",
                example = "Bosch GBH 8-45 DV",
                minLength = 1,
                maxLength = 200,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "must not be blank") @Size(min = 1, max = 200, message = "must contain between 1 and 200 characters") String name,

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
        @NotNull(message = "must not be null") InventoryStatus status) {

    public static final String SERIAL_NUMBER_PATTERN = "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$";

    public InventoryItemRequest {
        type = type == null ? null : type.strip();
        name = name == null ? null : name.strip();
    }
}
