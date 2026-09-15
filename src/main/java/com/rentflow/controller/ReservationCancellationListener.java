package com.rentflow.controller;

import org.apache.kafka.clients.consumer.ConsumerRecord;
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
    private static final String METRIC_NAME = "inventory.reservation.cancellation.records";

    private final ReservationCancellationEventConverter converter;
    private final InventoryService inventoryService;
    private final MeterRegistry metrics;

    public ReservationCancellationListener(
            ReservationCancellationEventConverter converter, InventoryService inventoryService, MeterRegistry metrics) {
        this.converter = converter;
        this.inventoryService = inventoryService;
        this.metrics = metrics;
    }

    @KafkaListener(topics = "${inventory.kafka.reservation-cancellation.topic}")
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
    }

    private void count(String outcome) {
        metrics.counter(METRIC_NAME, "outcome", outcome).increment();
    }
}
