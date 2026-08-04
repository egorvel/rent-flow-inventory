package com.rentflow.dto;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.Test;

import com.rentflow.model.InventoryStatus;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryStatusUpdateDTOTest {

    @Test
    void requiresAStatus() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(new InventoryStatusUpdateDTO(null)))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactly("status");
            assertThat(factory.getValidator().validate(new InventoryStatusUpdateDTO(InventoryStatus.RENTED)))
                    .isEmpty();
        }
    }
}
