package com.rentflow.service;

import com.rentflow.model.InventoryStatus;

public class InvalidInventoryStatusTransitionException extends RuntimeException {

    private final String serialNumber;
    private final InventoryStatus statusFrom;
    private final InventoryStatus statusTo;

    public InvalidInventoryStatusTransitionException(
            String serialNumber, InventoryStatus statusFrom, InventoryStatus statusTo) {
        super("Inventory item '" + serialNumber + "' cannot transition from " + statusFrom + " to " + statusTo + ".");
        this.serialNumber = serialNumber;
        this.statusFrom = statusFrom;
        this.statusTo = statusTo;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public InventoryStatus getStatusFrom() {
        return statusFrom;
    }

    public InventoryStatus getStatusTo() {
        return statusTo;
    }
}
