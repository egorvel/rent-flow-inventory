package com.rentflow.model;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryStatusTest {

    @Test
    void implementsTheExhaustiveTransitionMatrix() {
        assertTargets(
                InventoryStatus.AVAILABLE,
                Set.of(
                        InventoryStatus.RESERVED,
                        InventoryStatus.RENTED,
                        InventoryStatus.INSPECTION_REQUIRED,
                        InventoryStatus.UNDER_MAINTENANCE,
                        InventoryStatus.RETIRED));
        assertTargets(InventoryStatus.RESERVED, Set.of(InventoryStatus.AVAILABLE, InventoryStatus.RENTED));
        assertTargets(InventoryStatus.RENTED, Set.of(InventoryStatus.INSPECTION_REQUIRED));
        assertTargets(
                InventoryStatus.INSPECTION_REQUIRED,
                Set.of(InventoryStatus.AVAILABLE, InventoryStatus.UNDER_MAINTENANCE, InventoryStatus.RETIRED));
        assertTargets(InventoryStatus.UNDER_MAINTENANCE, Set.of(InventoryStatus.AVAILABLE, InventoryStatus.RETIRED));
        assertTargets(InventoryStatus.RETIRED, Set.of());
    }

    private void assertTargets(InventoryStatus source, Set<InventoryStatus> permittedTargets) {
        for (InventoryStatus target : InventoryStatus.values()) {
            assertThat(source.canTransitionTo(target))
                    .as("transition from %s to %s", source, target)
                    .isEqualTo(permittedTargets.contains(target));
        }
        assertThat(source.canTransitionTo(null)).isFalse();
    }
}
