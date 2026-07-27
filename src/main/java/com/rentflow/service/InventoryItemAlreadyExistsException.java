package com.rentflow.service;

public class InventoryItemAlreadyExistsException extends RuntimeException {

    private final String serialNumber;

    public InventoryItemAlreadyExistsException(String serialNumber) {
        super("Inventory item already exists: " + serialNumber);
        this.serialNumber = serialNumber;
    }

    public String getSerialNumber() {
        return serialNumber;
    }
}
