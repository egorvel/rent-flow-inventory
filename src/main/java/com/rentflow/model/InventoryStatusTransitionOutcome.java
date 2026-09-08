package com.rentflow.model;

import java.util.List;

public record InventoryStatusTransitionOutcome(
        int status, String type, String title, String detail, String instance, String code, List<Failure> failedItems) {
    public InventoryStatusTransitionOutcome {
        failedItems = List.copyOf(failedItems);
    }

    public static InventoryStatusTransitionOutcome success() {
        return new InventoryStatusTransitionOutcome(204, null, null, null, null, null, List.of());
    }

    public static InventoryStatusTransitionOutcome rejected(List<Failure> failures) {
        boolean conflict =
                failures.stream().anyMatch(failure -> failure.code().equals("INVALID_INVENTORY_STATUS_TRANSITION"));
        return new InventoryStatusTransitionOutcome(
                conflict ? 409 : 404,
                conflict
                        ? "urn:rentflow:problem:invalid-inventory-status-transition"
                        : "urn:rentflow:problem:inventory-item-not-found",
                conflict ? "Invalid inventory status transition" : "Inventory item not found",
                "No inventory statuses were changed.",
                "/api/v1/inventory/status",
                conflict ? "INVALID_INVENTORY_STATUS_TRANSITION" : "INVENTORY_ITEM_NOT_FOUND",
                failures);
    }

    public record Failure(int index, String serialNumber, InventoryStatus status, String code, String message) {}
}
