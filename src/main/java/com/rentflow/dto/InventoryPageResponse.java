package com.rentflow.dto;

import java.util.List;

public record InventoryPageResponse(
        List<InventoryItemResponse> items, int page, int size, long totalElements, int totalPages) {}
