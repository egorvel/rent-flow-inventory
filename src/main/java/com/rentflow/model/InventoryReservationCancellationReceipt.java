package com.rentflow.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "inventory_reservation_cancellation_inbox", schema = "inventory")
public class InventoryReservationCancellationReceipt {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "fingerprint", nullable = false, updatable = false, length = 64)
    private String fingerprint;

    @Column(name = "serial_number", nullable = false, updatable = false, length = 64)
    private String serialNumber;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, updatable = false, length = 32)
    private InventoryReservationCancellationOutcome outcome;

    @Column(name = "processed_at", nullable = false, insertable = false, updatable = false)
    private Instant processedAt;

    protected InventoryReservationCancellationReceipt() {}

    public InventoryReservationCancellationReceipt(
            UUID eventId,
            String fingerprint,
            String serialNumber,
            Instant occurredAt,
            InventoryReservationCancellationOutcome outcome) {
        this.eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint must not be null");
        this.serialNumber = Objects.requireNonNull(serialNumber, "serialNumber must not be null");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        this.outcome = Objects.requireNonNull(outcome, "outcome must not be null");
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public InventoryReservationCancellationOutcome getOutcome() {
        return outcome;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
