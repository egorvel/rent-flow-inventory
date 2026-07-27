package com.rentflow.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.rentflow.model.InventoryStatus;
import jakarta.validation.Validation;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class InventoryItemRequestTest {

    @Test
    void stripsOuterUnicodeWhitespaceFromTypeAndNameButNotSerialNumber() {
        var request = new InventoryItemRequest(
                " DRILL-001 ", "\u2003Industrial drill\u2003", "  Bosch GBH  ", InventoryStatus.AVAILABLE);

        assertThat(request.serialNumber()).isEqualTo(" DRILL-001 ");
        assertThat(request.type()).isEqualTo("Industrial drill");
        assertThat(request.name()).isEqualTo("Bosch GBH");
    }

    @Test
    void acceptsTheCompleteValidProfile() {
        var request = new InventoryItemRequest("DRILL_001.2", "Drill", "Bosch", InventoryStatus.AVAILABLE);

        assertThat(violations(request)).isEmpty();
    }

    @Test
    void rejectsMissingBlankOverlengthAndInvalidFields() {
        assertThat(violations(new InventoryItemRequest(null, null, null, null)))
                .containsExactlyInAnyOrder("serialNumber", "type", "name", "status");
        assertThat(violations(new InventoryItemRequest(" bad ", "  ", "  ", InventoryStatus.AVAILABLE)))
                .contains("serialNumber", "type", "name");
        assertThat(violations(new InventoryItemRequest(
                        "S".repeat(65), "T".repeat(101), "N".repeat(201), InventoryStatus.AVAILABLE)))
                .containsExactlyInAnyOrder("serialNumber", "type", "name");
    }

    private java.util.Set<String> violations(InventoryItemRequest request) {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            return factory.getValidator().validate(request).stream()
                    .map(violation -> violation.getPropertyPath().toString())
                    .collect(Collectors.toSet());
        }
    }
}
