package com.rentflow.converter;

import org.springframework.stereotype.Component;

import com.rentflow.dto.InventoryItemDTO;
import com.rentflow.dto.InventoryStatusTransitionFailureDTO;
import com.rentflow.dto.InventoryStatusTransitionProblemResponse;
import com.rentflow.model.InventoryItem;
import com.rentflow.model.InventoryStatusTransitionOutcome;

@Component
public class InventoryConverter {

    public InventoryItemDTO toResponse(InventoryItem item) {
        return new InventoryItemDTO(item.getSerialNumber(), item.getType(), item.getName(), item.getStatus());
    }

    public InventoryStatusTransitionProblemResponse toResponse(InventoryStatusTransitionOutcome outcome) {
        return new InventoryStatusTransitionProblemResponse(
                outcome.type(),
                outcome.title(),
                outcome.status(),
                outcome.detail(),
                outcome.instance(),
                outcome.code(),
                outcome.failedItems().stream()
                        .map(failure -> new InventoryStatusTransitionFailureDTO(
                                failure.index(),
                                failure.serialNumber(),
                                failure.status(),
                                failure.code(),
                                failure.message()))
                        .toList());
    }

    public InventoryItem toModel(InventoryItemDTO dto) {
        return new InventoryItem(dto.serialNumber(), dto.type(), dto.name(), dto.status());
    }
}
