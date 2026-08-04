package com.rentflow.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.rentflow.model.InventoryStatusHistory;

public interface InventoryStatusHistoryRepository
        extends JpaRepository<InventoryStatusHistory, Long>, JpaSpecificationExecutor<InventoryStatusHistory> {

    default Page<InventoryStatusHistory> findAll(String serialNumber, Pageable pageable) {
        return findAll(InventoryStatusHistorySpecifications.withSerialNumber(serialNumber), pageable);
    }
}
