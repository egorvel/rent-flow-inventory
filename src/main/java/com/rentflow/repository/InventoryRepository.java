package com.rentflow.repository;

import java.util.Optional;
import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.rentflow.model.InventoryItem;
import com.rentflow.model.InventoryStatus;

public interface InventoryRepository
        extends JpaRepository<InventoryItem, String>, JpaSpecificationExecutor<InventoryItem> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT item FROM InventoryItem item WHERE item.serialNumber = :serialNumber")
    Optional<InventoryItem> findByIdForUpdate(@Param("serialNumber") String serialNumber);

    default Page<InventoryItem> findAll(InventoryStatus status, String type, Pageable pageable) {
        return findAll(InventorySpecifications.withFilters(status, type), pageable);
    }
}
