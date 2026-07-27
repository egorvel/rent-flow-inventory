package com.rentflow.converter;

import com.rentflow.dto.InventoryItemResponse;
import com.rentflow.model.InventoryItem;
import org.springframework.stereotype.Component;

@Component
public class InventoryConverter {

    public InventoryItemResponse toResponse(InventoryItem item) {
        return new InventoryItemResponse(item.getSerialNumber(), item.getType(), item.getName(), item.getStatus());
    }
}
