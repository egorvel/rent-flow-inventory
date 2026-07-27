package com.rentflow.service;

import java.util.Arrays;

public enum InventorySortField {
    SERIAL_NUMBER("serialNumber", "serialNumber"),
    TYPE("type", "type"),
    NAME("name", "name"),
    STATUS("status", "status");

    private final String apiName;
    private final String entityAttribute;

    InventorySortField(String apiName, String entityAttribute) {
        this.apiName = apiName;
        this.entityAttribute = entityAttribute;
    }

    public String apiName() {
        return apiName;
    }

    public String entityAttribute() {
        return entityAttribute;
    }

    public static InventorySortField fromApiName(String apiName) {
        return Arrays.stream(values())
                .filter(field -> field.apiName.equals(apiName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported inventory sort field"));
    }
}
