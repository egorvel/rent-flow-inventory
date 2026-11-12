package com.rentflow.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rentflow.model.InventoryStatusTransition;
import com.rentflow.model.InventoryStatusTransitionOutcome;
import com.rentflow.model.InventoryStatusTransitionRequest;
import com.rentflow.model.InventoryStatusTransitionRequestId;
import com.rentflow.repository.InventoryStatusTransitionRequestRepository;
import com.rentflow.util.LengthPrefixedSha256;

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
    @PreAuthorize("@inventoryAuthorizationPolicy.canTransition(#transitions)")
    public Result transition(UUID key, List<InventoryStatusTransition> transitions) {
        String callerScope = callerScope();
        InventoryStatusTransitionRequestId id = new InventoryStatusTransitionRequestId(callerScope, key);
        String fingerprint = IdempotencyFingerprint.payload(transitions);
        if (!requestsRepository.tryExecutionLock(IdempotencyFingerprint.lockId(callerScope, key))) {
            count("busy");
            throw new IdempotencyException(true);
        }
        InventoryStatusTransitionRequest request =
                requestsRepository.findForUpdateById(id).orElse(null);
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
            request = new InventoryStatusTransitionRequest(id);
        }
        request.complete(fingerprint, outcome, requestsRepository.databaseTime());
        requestsRepository.saveAndFlush(request);
        return new Result(outcome, request.getExpiresAt(), false);
    }

    static String callerScope() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token) || !token.isAuthenticated()) {
            throw new AccessDeniedException("Authenticated identity required");
        }
        return "p1:"
                + LengthPrefixedSha256.hex(List.of(
                        "inventory-status-transition:principal:v1",
                        token.getToken().getClaimAsString(JwtClaimNames.ISS),
                        token.getToken().getSubject()));
    }

    private void count(String outcome) {
        metrics.counter("inventory.idempotency.requests", "outcome", outcome).increment();
    }

    public record Result(InventoryStatusTransitionOutcome outcome, Instant expiresAt, boolean replayed) {}
}
