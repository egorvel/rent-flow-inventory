package com.rentflow.controller;

import java.time.Clock;
import java.time.Duration;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.rentflow.converter.ReservationCancellationEventConverter;
import com.rentflow.model.ReservationCancellationEvent;
import com.rentflow.model.ReservationCancellationProcessingException;
import com.rentflow.model.ReservationCancellationProcessingOutcome;
import com.rentflow.model.ReservationCancellationRejectedException;
import com.rentflow.service.InventoryService;

import io.micrometer.core.instrument.MeterRegistry;

@Component
public class ReservationCancellationListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReservationCancellationListener.class);
    private static final String METRIC_NAME = "inventory.reservation.cancellation.records";

    private final ReservationCancellationEventConverter converter;
    private final InventoryService inventoryService;
    private final MeterRegistry metrics;
    private final Clock clock;

    public ReservationCancellationListener(
            ReservationCancellationEventConverter converter,
            InventoryService inventoryService,
            MeterRegistry metrics,
            Clock clock) {
        this.converter = converter;
        this.inventoryService = inventoryService;
        this.metrics = metrics;
        this.clock = clock;
    }

    @KafkaListener(
            id = "inventory-reservation-cancellation",
            topics = "${inventory.kafka.reservation-cancellation.topic}",
            idIsGroup = false)
    public void onCancellation(ConsumerRecord<byte[], byte[]> record) {
        count("received");
        ReservationCancellationEvent event = converter.convert(record);
        ReservationCancellationProcessingOutcome outcome;
        try {
            outcome = inventoryService.releaseAfterCancellation(event);
        } catch (ReservationCancellationRejectedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ReservationCancellationProcessingException(event.eventId(), exception);
        }
        count(
                switch (outcome) {
                    case RELEASED -> "released";
                    case ALREADY_AVAILABLE -> "already_available";
                    case DUPLICATE -> "duplicate";
                });
        LOGGER.atInfo()
                .addKeyValue("eventId", event.eventId().toString())
                .addKeyValue("serialNumber", event.serialNumber())
                .addKeyValue("outcome", outcome.name().toLowerCase(java.util.Locale.ROOT))
                .addKeyValue("sourceTopic", record.topic())
                .addKeyValue("partition", record.partition())
                .addKeyValue("offset", record.offset())
                .log("Reservation cancellation processed");
        if (outcome != ReservationCancellationProcessingOutcome.DUPLICATE) {
            Duration delay = Duration.between(event.occurredAt(), clock.instant());
            if (delay.isNegative()) {
                metrics.counter("inventory.reservation.cancellation.clock.skew").increment();
                LOGGER.atWarn()
                        .addKeyValue("eventId", event.eventId().toString())
                        .addKeyValue("serialNumber", event.serialNumber())
                        .addKeyValue("failureCode", "CLOCK_SKEW")
                        .log("Cancellation occurrence time is in the future");
            } else {
                metrics.timer("inventory.reservation.cancellation.completion.delay")
                        .record(delay);
            }
        }
    }

    private void count(String outcome) {
        metrics.counter(METRIC_NAME, "outcome", outcome).increment();
    }
}
