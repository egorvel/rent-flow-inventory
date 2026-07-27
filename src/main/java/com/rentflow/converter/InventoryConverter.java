package com.rentflow.converter;

import com.rentflow.dto.InventoryItemResponse;
import com.rentflow.dto.InventoryPageResponse;
import com.rentflow.model.InventoryItem;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

@Component
public class InventoryConverter {

    public InventoryItemResponse toResponse(InventoryItem item) {
        return new InventoryItemResponse(item.getSerialNumber(), item.getType(), item.getName(), item.getStatus());
    }

    public InventoryPageResponse toPageResponse(Page<InventoryItem> page) {
        return new InventoryPageResponse(
                page.getContent().stream().map(this::toResponse).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
