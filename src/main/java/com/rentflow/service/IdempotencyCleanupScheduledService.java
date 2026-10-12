package com.rentflow.service;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

@Service
public class IdempotencyCleanupScheduledService {
    private static final Logger LOGGER = LoggerFactory.getLogger(IdempotencyCleanupScheduledService.class);
    private final IdempotencyCleanupService cleanup;
    private final MeterRegistry metrics;
    private final long budgetNanos;
    private final ObservationRegistry observations;
    private final Clock clock;
    private final AtomicReference<Backlog> backlog = new AtomicReference<>(new Backlog(Double.NaN, 0));
    private final AtomicLong lastRun = new AtomicLong();
    private final AtomicLong lastSuccess = new AtomicLong();

    public IdempotencyCleanupScheduledService(
            IdempotencyCleanupService cleanup,
            MeterRegistry metrics,
            @Value("${inventory.idempotency.cleanup.runtime-budget:60s}") Duration budget,
            ObservationRegistry observations,
            Clock clock) {
        if (budget.isNegative() || budget.isZero()) {
            throw new IllegalArgumentException("Cleanup runtime budget must be positive");
        }
        this.cleanup = cleanup;
        this.metrics = metrics;
        this.budgetNanos = budget.toNanos();
        this.observations = observations;
        this.clock = clock;
        metrics.gauge(
                "inventory.idempotency.cleanup.expired.backlog",
                backlog,
                value -> value.get().count());
        metrics.gauge(
                "inventory.idempotency.cleanup.backlog.last.success.timestamp",
                backlog,
                value -> value.get().timestamp());
        metrics.gauge("inventory.idempotency.cleanup.last.run.timestamp", lastRun);
        metrics.gauge("inventory.idempotency.cleanup.last.success.timestamp", lastSuccess);
    }

    @Scheduled(fixedDelayString = "${inventory.idempotency.cleanup.backlog.refresh-delay:60s}")
    public void refreshBacklog() {
        try {
            long count = cleanup.countExpired();
            backlog.set(new Backlog(count, clock.instant().getEpochSecond()));
        } catch (RuntimeException exception) {
            metrics.counter("inventory.idempotency.cleanup.backlog.refresh.failures")
                    .increment();
            LOGGER.atWarn()
                    .addKeyValue("exceptionType", exception.getClass().getName())
                    .log("Idempotency backlog refresh failed; retaining the previous measurement");
        }
    }

    @Scheduled(cron = "${inventory.idempotency.cleanup.cron:0 0 3 * * *}", zone = "UTC")
    public void cleanup() {
        long started = System.nanoTime();
        lastRun.set(clock.instant().getEpochSecond());
        Timer.Sample sample = Timer.start(metrics);
        Observation observation = Observation.start("inventory.idempotency.cleanup.run", observations);
        try (Observation.Scope _ = observation.openScope()) {
            try {
                int deleted;
                do {
                    deleted = cleanup.deleteChunk();
                    metrics.counter("inventory.idempotency.cleanup.deleted").increment(deleted);
                } while (deleted == 1000 && System.nanoTime() - started < budgetNanos);
                lastSuccess.set(clock.instant().getEpochSecond());
                refreshBacklog();
            } catch (RuntimeException exception) {
                observation.error(exception);
                metrics.counter("inventory.idempotency.cleanup.failures").increment();
                LOGGER.atWarn()
                        .addKeyValue("exceptionType", exception.getClass().getName())
                        .setCause(exception)
                        .log("Idempotency cleanup failed; the next scheduled run will retry");
            }
        } finally {
            sample.stop(metrics.timer("inventory.idempotency.cleanup.duration"));
            observation.stop();
        }
    }

    private record Backlog(double count, long timestamp) {}
}
