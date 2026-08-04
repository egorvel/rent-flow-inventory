package com.rentflow.repository;

import java.util.Optional;
import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;

import com.rentflow.model.InventoryItem;
import com.rentflow.model.InventoryStatus;

public interface InventoryRepository
        extends JpaRepository<InventoryItem, String>, JpaSpecificationExecutor<InventoryItem> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<InventoryItem> findForUpdateBySerialNumber(String serialNumber);

    default Page<InventoryItem> findAll(InventoryStatus status, String type, Pageable pageable) {
        return findAll(InventorySpecifications.withFilters(status, type), pageable);
    }
}
