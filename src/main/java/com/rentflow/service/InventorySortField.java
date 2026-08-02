package com.rentflow.service;

import java.util.Arrays;

public enum InventorySortField {
    SERIAL_NUMBER("serialNumber"),
    TYPE("type"),
    NAME("name"),
    STATUS("status");

    private final String property;

    InventorySortField(String property) {
        this.property = property;
    }

    public String property() {
        return property;
    }

    public static InventorySortField fromApiName(String apiName) {
        return Arrays.stream(values())
                .filter(field -> field.property.equals(apiName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported inventory sort field"));
    }
}
