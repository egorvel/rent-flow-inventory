package com.rentflow.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rentflow.model.InventoryStatusTransition;
import com.rentflow.model.InventoryStatusTransitionOutcome;
import com.rentflow.model.InventoryStatusTransitionRequest;
import com.rentflow.repository.InventoryStatusTransitionRequestRepository;

import io.micrometer.core.instrument.MeterRegistry;

@Service
public class IdempotentStatusTransitionService {
    private final InventoryService inventory;
    private final InventoryStatusTransitionRequestRepository requestsRepository;
    private final MeterRegistry metrics;

    public IdempotentStatusTransitionService(
            InventoryService inventory,
            InventoryStatusTransitionRequestRepository requestsRepository,
            MeterRegistry metrics) {
        this.inventory = inventory;
        this.requestsRepository = requestsRepository;
        this.metrics = metrics;
    }

    @Transactional
    public Result transition(UUID key, List<InventoryStatusTransition> transitions) {
        String fingerprint = IdempotencyFingerprint.payload(transitions);
        if (!requestsRepository.tryExecutionLock(IdempotencyFingerprint.lockId(key))) {
            count("busy");
            throw new IdempotencyException(true);
        }
        InventoryStatusTransitionRequest request =
                requestsRepository.findForUpdateByKey(key).orElse(null);
        if (request != null && request.getExpiresAt().isAfter(requestsRepository.databaseTime())) {
            if (!request.getFingerprint().equals(fingerprint)) {
                count("mismatch");
                throw new IdempotencyException(false);
            }
            count("replay");
            return new Result(request.getOutcome(), request.getExpiresAt(), true);
        }
        count("attempt");
        InventoryStatusTransitionOutcome outcome = inventory.transitionStatus(transitions);
        // Flush item/history writes before recording the completion time, in this same transaction.
        requestsRepository.flush();
        if (request == null) {
            request = new InventoryStatusTransitionRequest(key);
        }
        request.complete(fingerprint, outcome, requestsRepository.databaseTime());
        requestsRepository.saveAndFlush(request);
        return new Result(outcome, request.getExpiresAt(), false);
    }

    private void count(String outcome) {
        metrics.counter("inventory.idempotency.requests", "outcome", outcome).increment();
    }

    public record Result(InventoryStatusTransitionOutcome outcome, Instant expiresAt, boolean replayed) {}
}
