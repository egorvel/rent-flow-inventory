package com.rentflow.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ReservationCancellationEvent(
        UUID eventId, Instant occurredAt, String serialNumber, String fingerprint, long lockId) {

    public ReservationCancellationEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(serialNumber, "serialNumber must not be null");
        Objects.requireNonNull(fingerprint, "fingerprint must not be null");
    }
}
