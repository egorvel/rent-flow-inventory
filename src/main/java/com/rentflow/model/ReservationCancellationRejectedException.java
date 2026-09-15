package com.rentflow.model;

import java.util.Optional;
import java.util.UUID;

public class ReservationCancellationRejectedException extends RuntimeException {
    private final ReservationCancellationFailureCode failureCode;
    private final UUID eventId;

    public ReservationCancellationRejectedException(ReservationCancellationFailureCode failureCode, UUID eventId) {
        super("Reservation cancellation rejected: " + failureCode);
        this.failureCode = failureCode;
        this.eventId = eventId;
    }

    public ReservationCancellationFailureCode getFailureCode() {
        return failureCode;
    }

    public Optional<UUID> getEventId() {
        return Optional.ofNullable(eventId);
    }
}
