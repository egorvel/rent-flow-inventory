package com.rentflow.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.rentflow.model.InventoryStatus;
import com.rentflow.model.InventoryStatusTransition;
import com.rentflow.model.InventoryStatusTransitionOutcome;
import com.rentflow.model.InventoryStatusTransitionRequest;
import com.rentflow.repository.InventoryStatusTransitionRequestRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class IdempotentStatusTransitionServiceTest {
    @Test
    void fingerprintUsesOrderedLengthPrefixedValidatedValuesAndStableNamespace() {
        List<InventoryStatusTransition> input = List.of(new InventoryStatusTransition("A", InventoryStatus.RESERVED));
        assertThat(IdempotencyFingerprint.payload(input))
                .isEqualTo("435c244db4a2057557400ef056744fa13ffcdf7239eb732ebc8da7e58865237c");
        assertThat(IdempotencyFingerprint.payload(
                        List.of(new InventoryStatusTransition("a", InventoryStatus.RESERVED))))
                .isNotEqualTo(IdempotencyFingerprint.payload(input));
        assertThat(IdempotencyFingerprint.lockId(UUID.fromString("00000000-0000-4000-8000-000000000001")))
                .isEqualTo(-4063175354861834424L);
    }

    @Test
    void replayAndMismatchNeverAccessInventoryAndMetricsHaveBoundedLabels() {
        InventoryService inventory = mock(InventoryService.class);
        InventoryStatusTransitionRequestRepository requests = mock(InventoryStatusTransitionRequestRepository.class);
        UUID key = UUID.randomUUID();
        List<InventoryStatusTransition> input = List.of(new InventoryStatusTransition("A", InventoryStatus.RESERVED));
        InventoryStatusTransitionRequest saved = new InventoryStatusTransitionRequest(key);
        saved.complete(
                IdempotencyFingerprint.payload(input),
                InventoryStatusTransitionOutcome.success(),
                Instant.parse("2026-09-01T00:00:00Z"));
        when(requests.tryExecutionLock(IdempotencyFingerprint.lockId(key))).thenReturn(true, true, false);
        when(requests.findForUpdateByKey(key)).thenReturn(Optional.of(saved));
        when(requests.databaseTime()).thenReturn(Instant.parse("2026-09-02T00:00:00Z"));
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        try {
            IdempotentStatusTransitionService service =
                    new IdempotentStatusTransitionService(inventory, requests, metrics);
            assertThat(service.transition(key, input).replayed()).isTrue();
            assertThatThrownBy(() -> service.transition(
                            key, List.of(new InventoryStatusTransition("A", InventoryStatus.RENTED))))
                    .isInstanceOfSatisfying(
                            IdempotencyException.class,
                            error -> assertThat(error.isInProgress()).isFalse());
            assertThatThrownBy(() -> service.transition(key, input))
                    .isInstanceOfSatisfying(
                            IdempotencyException.class,
                            error -> assertThat(error.isInProgress()).isTrue());
            verifyNoInteractions(inventory);
            verify(requests, never()).saveAndFlush(any());
            for (String outcome : List.of("replay", "mismatch", "busy")) {
                assertThat(metrics.get("inventory.idempotency.requests")
                                .tag("outcome", outcome)
                                .counter()
                                .count())
                        .isOne();
            }
            assertThat(metrics.getMeters())
                    .allSatisfy(meter -> assertThat(meter.getId().getTags()).hasSize(1));
        } finally {
            metrics.close();
        }
    }
}
