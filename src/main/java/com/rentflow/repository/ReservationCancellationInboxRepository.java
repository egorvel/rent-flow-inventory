package com.rentflow.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.rentflow.model.InventoryReservationCancellationReceipt;

public interface ReservationCancellationInboxRepository
        extends JpaRepository<InventoryReservationCancellationReceipt, UUID> {

    @Query(value = "SELECT pg_advisory_xact_lock(:lockId)", nativeQuery = true)
    void acquireEventLock(long lockId);
}
