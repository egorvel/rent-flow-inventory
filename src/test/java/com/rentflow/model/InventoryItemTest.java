package com.rentflow.model;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class InventoryItemTest {

    @Test
    void keepsAssignedSerialNumberWhenDetailsAreReplaced() {
        InventoryItem item = new InventoryItem("DRILL-001", "Drill", "Original", InventoryStatus.AVAILABLE);

        item.replaceDetails("Industrial drill", "Updated", InventoryStatus.RENTED);

        assertThat(item.getSerialNumber()).isEqualTo("DRILL-001");
        assertThat(item.getType()).isEqualTo("Industrial drill");
        assertThat(item.getName()).isEqualTo("Updated");
        assertThat(item.getStatus()).isEqualTo(InventoryStatus.RENTED);
    }

    @Test
    void transitionsOnlyTheStatus() {
        InventoryItem item = new InventoryItem("DRILL-001", "Drill", "Original", InventoryStatus.RESERVED);

        item.transitionStatus(InventoryStatus.RENTED);

        assertThat(item.getSerialNumber()).isEqualTo("DRILL-001");
        assertThat(item.getType()).isEqualTo("Drill");
        assertThat(item.getName()).isEqualTo("Original");
        assertThat(item.getStatus()).isEqualTo(InventoryStatus.RENTED);
        assertThatNullPointerException().isThrownBy(() -> item.transitionStatus(null));
    }

    @Test
    void exposesNoSerialNumberMutator() {
        assertThat(InventoryItem.class.getMethods()).extracting(Method::getName).doesNotContain("setSerialNumber");
    }

    @Test
    void usesAssignedSerialNumberForEquality() {
        InventoryItem first = new InventoryItem("DRILL-001", "Drill", "First", InventoryStatus.AVAILABLE);
        InventoryItem sameIdentity = new InventoryItem("DRILL-001", "Other", "Second", InventoryStatus.RETIRED);
        InventoryItem differentIdentity = new InventoryItem("drill-001", "Drill", "First", InventoryStatus.AVAILABLE);

        assertThat(first).isEqualTo(sameIdentity).hasSameHashCodeAs(sameIdentity);
        assertThat(first).isNotEqualTo(differentIdentity);
    }

    @Test
    void requiresEveryConstructorAndReplacementValue() {
        assertThatNullPointerException()
                .isThrownBy(() -> new InventoryItem(null, "Drill", "Name", InventoryStatus.AVAILABLE));
        assertThatNullPointerException()
                .isThrownBy(() -> new InventoryItem("DRILL-001", null, "Name", InventoryStatus.AVAILABLE));
        assertThatNullPointerException()
                .isThrownBy(() -> new InventoryItem("DRILL-001", "Drill", null, InventoryStatus.AVAILABLE));
        assertThatNullPointerException().isThrownBy(() -> new InventoryItem("DRILL-001", "Drill", "Name", null));
    }
}
