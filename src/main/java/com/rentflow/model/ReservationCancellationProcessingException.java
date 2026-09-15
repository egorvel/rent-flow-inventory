package com.rentflow.model;

import java.util.UUID;

public class ReservationCancellationProcessingException extends RuntimeException {
    private final UUID eventId;

    public ReservationCancellationProcessingException(UUID eventId, RuntimeException cause) {
        super("Reservation cancellation processing failed", cause);
        this.eventId = eventId;
    }

    public UUID getEventId() {
        return eventId;
    }
}
