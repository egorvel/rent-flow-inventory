package com.rentflow.service;

import java.util.List;

import com.rentflow.model.InventoryStatus;

public class InventoryStatusTransitionBatchException extends RuntimeException {

    private final List<Failure> failures;

    public InventoryStatusTransitionBatchException(List<Failure> failures) {
        super("No inventory statuses were changed.");
        this.failures = List.copyOf(failures);
    }

    public List<Failure> getFailures() {
        return failures;
    }

    public record Failure(int index, String serialNumber, InventoryStatus status, String code, String message) {}
}
