package com.rentflow.repository;

import org.springframework.data.jpa.domain.Specification;

import com.rentflow.model.InventoryStatusHistory;

final class InventoryStatusHistorySpecifications {

    private InventoryStatusHistorySpecifications() {}

    static Specification<InventoryStatusHistory> withSerialNumber(String serialNumber) {
        if (serialNumber == null) {
            return (_, _, builder) -> builder.conjunction();
        }
        String escaped = serialNumber.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return (root, _, builder) -> builder.like(root.get("serialNumber"), "%" + escaped + "%", '\\');
    }
}
