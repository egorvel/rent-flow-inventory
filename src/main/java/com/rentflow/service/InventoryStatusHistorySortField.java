package com.rentflow.service;

import java.util.Arrays;

public enum InventoryStatusHistorySortField {
    SERIAL_NUMBER("serialNumber"),
    STATUS_FROM("statusFrom"),
    STATUS_TO("statusTo"),
    TIMESTAMP("timestamp");

    private final String property;

    InventoryStatusHistorySortField(String property) {
        this.property = property;
    }

    public String property() {
        return property;
    }

    public static InventoryStatusHistorySortField fromApiName(String apiName) {
        return Arrays.stream(values())
                .filter(field -> field.property.equals(apiName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported inventory history sort field"));
    }
}
