package com.rentflow.service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Service
public class IdempotencyCleanupScheduledService {
    private static final Logger LOGGER = LoggerFactory.getLogger(IdempotencyCleanupScheduledService.class);
    private final IdempotencyCleanupService cleanup;
    private final MeterRegistry metrics;
    private final long budgetNanos;
    private final AtomicLong backlog = new AtomicLong();

    public IdempotencyCleanupScheduledService(
            IdempotencyCleanupService cleanup,
            MeterRegistry metrics,
            @Value("${inventory.idempotency.cleanup.runtime-budget:60s}") Duration budget) {
        if (budget.isNegative() || budget.isZero()) {
            throw new IllegalArgumentException("Cleanup runtime budget must be positive");
        }
        this.cleanup = cleanup;
        this.metrics = metrics;
        this.budgetNanos = budget.toNanos();
        metrics.gauge("inventory.idempotency.cleanup.expired.backlog", backlog);
    }

    @Scheduled(cron = "${inventory.idempotency.cleanup.cron:0 0 3 * * *}", zone = "UTC")
    public void cleanup() {
        long started = System.nanoTime();
        Timer.Sample sample = Timer.start(metrics);
        try {
            int deleted;
            do {
                deleted = cleanup.deleteChunk();
                metrics.counter("inventory.idempotency.cleanup.deleted").increment(deleted);
            } while (deleted == 1000 && System.nanoTime() - started < budgetNanos);
            backlog.set(cleanup.countExpired());
        } catch (RuntimeException exception) {
            metrics.counter("inventory.idempotency.cleanup.failures").increment();
            LOGGER.warn("Idempotency cleanup failed; the next scheduled run will retry.");
        } finally {
            sample.stop(metrics.timer("inventory.idempotency.cleanup.duration"));
        }
    }
}
