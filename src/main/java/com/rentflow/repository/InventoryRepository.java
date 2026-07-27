package com.rentflow.repository;

import com.rentflow.model.InventoryItem;
import com.rentflow.model.InventoryStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface InventoryRepository
        extends JpaRepository<InventoryItem, String>, JpaSpecificationExecutor<InventoryItem> {

    default Page<InventoryItem> findAll(InventoryStatus status, String type, Pageable pageable) {
        return findAll(InventorySpecifications.withFilters(status, type), pageable);
    }
}
