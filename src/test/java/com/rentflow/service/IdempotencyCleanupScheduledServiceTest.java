package com.rentflow.service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class IdempotencyCleanupScheduledServiceTest {
    @Test
    void backlogStartsUnknownAndRefreshFailureRetainsValueAndFreshness() {
        IdempotencyCleanupService cleanup = mock(IdempotencyCleanupService.class);
        java.time.Instant now = java.time.Instant.parse("2026-10-09T10:00:00Z");
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(now, now.plusSeconds(120));
        when(cleanup.countExpired())
                .thenReturn(7L)
                .thenThrow(new IllegalStateException("synthetic-private-value"))
                .thenReturn(3L);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        try {
            IdempotencyCleanupScheduledService service = new IdempotencyCleanupScheduledService(
                    cleanup, metrics, Duration.ofSeconds(60), ObservationRegistry.NOOP, clock);
            assertThat(metrics.get("inventory.idempotency.cleanup.expired.backlog")
                            .gauge()
                            .value())
                    .isNaN();
            assertThat(metrics.get("inventory.idempotency.cleanup.backlog.last.success.timestamp")
                            .gauge()
                            .value())
                    .isZero();
            service.refreshBacklog();
            service.refreshBacklog();
            assertThat(metrics.get("inventory.idempotency.cleanup.expired.backlog")
                            .gauge()
                            .value())
                    .isEqualTo(7);
            assertThat(metrics.get("inventory.idempotency.cleanup.backlog.last.success.timestamp")
                            .gauge()
                            .value())
                    .isEqualTo(now.getEpochSecond());
            assertThat(metrics.get("inventory.idempotency.cleanup.backlog.refresh.failures")
                            .counter()
                            .count())
                    .isOne();
            verify(cleanup, times(2)).countExpired();
            service.refreshBacklog();
            assertThat(metrics.get("inventory.idempotency.cleanup.expired.backlog")
                            .gauge()
                            .value())
                    .isEqualTo(3);
            assertThat(metrics.get("inventory.idempotency.cleanup.backlog.last.success.timestamp")
                            .gauge()
                            .value())
                    .isEqualTo(now.plusSeconds(120).getEpochSecond());
            verify(cleanup, times(3)).countExpired();
        } finally {
            metrics.close();
        }
    }

    @Test
    void measurementFailureDoesNotMarkSuccessfulDeletionAsFailed() {
        IdempotencyCleanupService cleanup = mock(IdempotencyCleanupService.class);
        when(cleanup.deleteChunk()).thenReturn(0);
        when(cleanup.countExpired()).thenThrow(new IllegalStateException("synthetic-private-value"));
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        try {
            IdempotencyCleanupScheduledService service = new IdempotencyCleanupScheduledService(
                    cleanup,
                    metrics,
                    Duration.ofSeconds(60),
                    ObservationRegistry.NOOP,
                    Clock.fixed(java.time.Instant.ofEpochSecond(1234), java.time.ZoneOffset.UTC));
            service.cleanup();
            assertThat(metrics.find("inventory.idempotency.cleanup.failures").counter())
                    .isNull();
            assertThat(metrics.get("inventory.idempotency.cleanup.last.run.timestamp")
                            .gauge()
                            .value())
                    .isEqualTo(1234);
            assertThat(metrics.get("inventory.idempotency.cleanup.last.success.timestamp")
                            .gauge()
                            .value())
                    .isEqualTo(1234);
            assertThat(metrics.get("inventory.idempotency.cleanup.backlog.refresh.failures")
                            .counter()
                            .count())
                    .isOne();
        } finally {
            metrics.close();
        }
    }

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
            new IdempotencyCleanupScheduledService(
                            cleanup, metrics, Duration.ofSeconds(60), ObservationRegistry.NOOP, Clock.systemUTC())
                    .cleanup();
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
            new IdempotencyCleanupScheduledService(
                            cleanup, metrics, Duration.ofNanos(1), ObservationRegistry.NOOP, Clock.systemUTC())
                    .cleanup();
            verify(cleanup).deleteChunk();
            verify(cleanup).countExpired();
            verifyNoMoreInteractions(cleanup);
        } finally {
            metrics.close();
        }
    }

    @Test
    void failureWarningUsesCleanupObservationAndRestoresParentScope() {
        IdempotencyCleanupService cleanup = mock(IdempotencyCleanupService.class);
        IllegalStateException failure = new IllegalStateException("forced failure");
        when(cleanup.deleteChunk()).thenThrow(failure);
        ObservationRegistry observations = ObservationRegistry.create();
        List<Observation.Context> finished = new ArrayList<>();
        observations.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return context.getName().equals("inventory.idempotency.cleanup.run");
            }

            @Override
            public void onStop(Observation.Context context) {
                finished.add(context);
            }
        });
        List<Observation.Context> warningContexts = new ArrayList<>();
        AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                if (event.getMessage().equals("Idempotency cleanup failed; the next scheduled run will retry")) {
                    Observation current = observations.getCurrentObservation();
                    warningContexts.add(current == null ? null : current.getContext());
                }
            }
        };
        Logger logger = (Logger) LoggerFactory.getLogger(IdempotencyCleanupScheduledService.class);
        Level previousLevel = logger.getLevel();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.setLevel(Level.WARN);
        logger.addAppender(appender);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        try {
            IdempotencyCleanupScheduledService service = new IdempotencyCleanupScheduledService(
                    cleanup, metrics, Duration.ofSeconds(60), observations, Clock.systemUTC());
            Observation parent = Observation.start("parent", observations);
            try (Observation.Scope scope = parent.openScope()) {
                service.cleanup();
                assertThat(observations.getCurrentObservation()).isSameAs(parent);
                assertThat(finished).hasSize(1);
                assertThat(finished.getFirst().getError()).isSameAs(failure);
                assertThat(warningContexts).containsExactly(finished.getFirst());
            } finally {
                parent.stop();
            }
            assertThat(observations.getCurrentObservation()).isNull();
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
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
        ObservationRegistry observations = ObservationRegistry.create();
        java.util.List<io.micrometer.observation.Observation.Context> finished = new java.util.ArrayList<>();
        observations
                .observationConfig()
                .observationHandler(
                        new io.micrometer.observation.ObservationHandler<
                                io.micrometer.observation.Observation.Context>() {
                            @Override
                            public boolean supportsContext(io.micrometer.observation.Observation.Context context) {
                                return context.getName().equals("inventory.idempotency.cleanup.run");
                            }

                            @Override
                            public void onStop(io.micrometer.observation.Observation.Context context) {
                                finished.add(context);
                            }
                        });
        try {
            IdempotencyCleanupScheduledService service = new IdempotencyCleanupScheduledService(
                    cleanup, metrics, Duration.ofSeconds(60), observations, Clock.systemUTC());
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
            assertThat(finished).hasSize(2);
            assertThat(finished.getFirst().getError()).isInstanceOf(IllegalStateException.class);
            assertThat(finished.getLast().getError()).isNull();
        } finally {
            metrics.close();
        }
    }
}
