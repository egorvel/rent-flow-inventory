package com.rentflow.converter;

import com.rentflow.dto.InventoryItemDTO;
import com.rentflow.dto.InventoryPageResponse;
import com.rentflow.model.InventoryItem;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

@Component
public class InventoryConverter {

    public InventoryItemDTO toResponse(InventoryItem item) {
        return new InventoryItemDTO(item.getSerialNumber(), item.getType(), item.getName(), item.getStatus());
    }

    public InventoryItem toModel(InventoryItemDTO dto) {
        return new InventoryItem(dto.serialNumber(), dto.type(), dto.name(), dto.status());
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
