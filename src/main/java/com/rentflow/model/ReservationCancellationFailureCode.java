package com.rentflow.model;

public enum ReservationCancellationFailureCode {
    INVALID_EVENT,
    UNSUPPORTED_EVENT,
    EVENT_ID_REUSED,
    INVENTORY_ITEM_NOT_FOUND,
    INVALID_INVENTORY_STATUS,
    PROCESSING_RETRIES_EXHAUSTED
}
