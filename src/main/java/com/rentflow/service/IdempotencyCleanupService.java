package com.rentflow.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.rentflow.repository.InventoryStatusTransitionRequestRepository;

@Service
public class IdempotencyCleanupService {
    private final InventoryStatusTransitionRequestRepository requests;

    public IdempotencyCleanupService(InventoryStatusTransitionRequestRepository requests) {
        this.requests = requests;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteChunk() {
        return requests.deleteExpiredChunk();
    }

    @Transactional(readOnly = true)
    public long countExpired() {
        return requests.countExpired();
    }
}
