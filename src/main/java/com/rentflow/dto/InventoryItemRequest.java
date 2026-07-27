package com.rentflow.dto;

import com.rentflow.model.InventoryStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record InventoryItemRequest(
        @NotBlank(message = "must not be blank") @Pattern(
                regexp = SERIAL_NUMBER_PATTERN,
                message =
                        "must start with an alphanumeric character and contain only alphanumeric characters, dots, underscores, or hyphens")
        String serialNumber,

        @NotBlank(message = "must not be blank") @Size(max = 100, message = "must contain at most 100 characters") String type,

        @NotBlank(message = "must not be blank") @Size(max = 200, message = "must contain at most 200 characters") String name,

        @NotNull(message = "must not be null") InventoryStatus status) {

    public static final String SERIAL_NUMBER_PATTERN = "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$";

    public InventoryItemRequest {
        type = type == null ? null : type.strip();
        name = name == null ? null : name.strip();
    }
}
