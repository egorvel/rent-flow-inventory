package com.rentflow.service;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class IdempotencyCleanupScheduledServiceTest {
    @Test
    void defaultsToDailyUtcAndContinuesFullChunksInSeparateCalls() throws Exception {
        Scheduled schedule =
                IdempotencyCleanupScheduledService.class.getMethod("cleanup").getAnnotation(Scheduled.class);
        assertThat(schedule.cron()).isEqualTo("${inventory.idempotency.cleanup.cron:0 0 3 * * *}");
        assertThat(schedule.zone()).isEqualTo("UTC");
        IdempotencyCleanupService cleanup = mock(IdempotencyCleanupService.class);
        when(cleanup.deleteChunk()).thenReturn(1000, 1000, 3);
        when(cleanup.countExpired()).thenReturn(7L);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        try {
            new IdempotencyCleanupScheduledService(cleanup, metrics, Duration.ofSeconds(60)).cleanup();
            verify(cleanup, times(3)).deleteChunk();
            assertThat(metrics.get("inventory.idempotency.cleanup.deleted")
                            .counter()
                            .count())
                    .isEqualTo(2003);
            assertThat(metrics.get("inventory.idempotency.cleanup.duration")
                            .timer()
                            .count())
                    .isOne();
            assertThat(metrics.get("inventory.idempotency.cleanup.expired.backlog")
                            .gauge()
                            .value())
                    .isEqualTo(7);
            assertThat(metrics.getMeters())
                    .allSatisfy(meter -> assertThat(meter.getId().getTags()).isEmpty());
        } finally {
            metrics.close();
        }
    }

    @Test
    void doesNotStartAnotherChunkAfterBudgetExpires() {
        IdempotencyCleanupService cleanup = mock(IdempotencyCleanupService.class);
        when(cleanup.deleteChunk()).thenReturn(1000);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        try {
            new IdempotencyCleanupScheduledService(cleanup, metrics, Duration.ofNanos(1)).cleanup();
            verify(cleanup).deleteChunk();
            verify(cleanup).countExpired();
            verifyNoMoreInteractions(cleanup);
        } finally {
            metrics.close();
        }
    }

    @Test
    void recordsFailureAndDurationAndRetriesOnNextRun() {
        IdempotencyCleanupService cleanup = mock(IdempotencyCleanupService.class);
        when(cleanup.deleteChunk())
                .thenThrow(new IllegalStateException("forced failure"))
                .thenReturn(0);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        try {
            IdempotencyCleanupScheduledService service =
                    new IdempotencyCleanupScheduledService(cleanup, metrics, Duration.ofSeconds(60));
            service.cleanup();
            verify(cleanup, never()).countExpired();
            assertThat(metrics.get("inventory.idempotency.cleanup.failures")
                            .counter()
                            .count())
                    .isOne();
            service.cleanup();
            assertThat(metrics.get("inventory.idempotency.cleanup.duration")
                            .timer()
                            .count())
                    .isEqualTo(2);
            verify(cleanup).countExpired();
        } finally {
            metrics.close();
        }
    }
}
