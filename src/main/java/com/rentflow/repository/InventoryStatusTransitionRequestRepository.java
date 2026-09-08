package com.rentflow.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.rentflow.model.InventoryStatusTransitionRequest;

public interface InventoryStatusTransitionRequestRepository
        extends JpaRepository<InventoryStatusTransitionRequest, UUID> {
    @Query(value = "SELECT pg_try_advisory_xact_lock(:lockId)", nativeQuery = true)
    boolean tryExecutionLock(long lockId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<InventoryStatusTransitionRequest> findForUpdateByKey(UUID key);

    @Query(value = "SELECT clock_timestamp()", nativeQuery = true)
    Instant databaseTime();

    @Modifying
    @Query(value = """
            WITH expired AS (
                SELECT idempotency_key FROM inventory.inventory_status_transition_requests
                WHERE expires_at <= statement_timestamp()
                ORDER BY expires_at, idempotency_key
                LIMIT 1000 FOR UPDATE SKIP LOCKED
            )
            DELETE FROM inventory.inventory_status_transition_requests r
            USING expired e WHERE r.idempotency_key = e.idempotency_key
            """, nativeQuery = true)
    int deleteExpiredChunk();

    @Query(
            value =
                    "SELECT count(*) FROM inventory.inventory_status_transition_requests WHERE expires_at <= statement_timestamp()",
            nativeQuery = true)
    long countExpired();
}
