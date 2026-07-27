package com.rentflow.dto;

import com.rentflow.model.InventoryStatus;

public record InventoryItemResponse(String serialNumber, String type, String name, InventoryStatus status) {}
