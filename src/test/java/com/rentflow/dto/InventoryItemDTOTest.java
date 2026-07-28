package com.rentflow.dto;

import java.util.stream.Collectors;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.Test;

import com.rentflow.model.InventoryStatus;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryItemDTOTest {

    @Test
    void stripsOuterUnicodeWhitespaceFromTypeAndNameButNotSerialNumber() {
        InventoryItemDTO request = new InventoryItemDTO(
                " DRILL-001 ", "\u2003Industrial drill\u2003", "  Bosch GBH  ", InventoryStatus.AVAILABLE);

        assertThat(request.serialNumber()).isEqualTo(" DRILL-001 ");
        assertThat(request.type()).isEqualTo("Industrial drill");
        assertThat(request.name()).isEqualTo("Bosch GBH");
    }

    @Test
    void acceptsTheCompleteValidProfile() {
        InventoryItemDTO request = new InventoryItemDTO("DRILL_001.2", "Drill", "Bosch", InventoryStatus.AVAILABLE);

        assertThat(violations(request)).isEmpty();
    }

    @Test
    void rejectsMissingBlankOverlengthAndInvalidFields() {
        assertThat(violations(new InventoryItemDTO(null, null, null, null)))
                .containsExactlyInAnyOrder("serialNumber", "type", "name", "status");
        assertThat(violations(new InventoryItemDTO(" bad ", "  ", "  ", InventoryStatus.AVAILABLE)))
                .contains("serialNumber", "type", "name");
        assertThat(violations(new InventoryItemDTO(
                        "S".repeat(65), "T".repeat(101), "N".repeat(201), InventoryStatus.AVAILABLE)))
                .containsExactlyInAnyOrder("serialNumber", "type", "name");
    }

    private java.util.Set<String> violations(InventoryItemDTO request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            return factory.getValidator().validate(request).stream()
                    .map(violation -> violation.getPropertyPath().toString())
                    .collect(Collectors.toSet());
        }
    }
}
