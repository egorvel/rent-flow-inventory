package com.rentflow.converter;

import org.springframework.stereotype.Component;

import com.rentflow.dto.InventoryStatusHistoryDTO;
import com.rentflow.model.InventoryStatusHistory;

@Component
public class InventoryStatusHistoryConverter {

    public InventoryStatusHistoryDTO toResponse(InventoryStatusHistory history) {
        return new InventoryStatusHistoryDTO(
                history.getSerialNumber(), history.getStatusFrom(), history.getStatusTo(), history.getTimestamp());
    }
}
