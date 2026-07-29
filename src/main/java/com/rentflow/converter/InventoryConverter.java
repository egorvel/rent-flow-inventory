package com.rentflow.converter;

import org.springframework.stereotype.Component;

import com.rentflow.dto.InventoryItemDTO;
import com.rentflow.model.InventoryItem;

@Component
public class InventoryConverter {

    public InventoryItemDTO toResponse(InventoryItem item) {
        return new InventoryItemDTO(item.getSerialNumber(), item.getType(), item.getName(), item.getStatus());
    }

    public InventoryItem toModel(InventoryItemDTO dto) {
        return new InventoryItem(dto.serialNumber(), dto.type(), dto.name(), dto.status());
    }
}
