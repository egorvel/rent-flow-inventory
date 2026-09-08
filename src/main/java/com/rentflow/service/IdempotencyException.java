package com.rentflow.service;

public class IdempotencyException extends RuntimeException {
    private final boolean inProgress;

    public IdempotencyException(boolean inProgress) {
        super(
                inProgress
                        ? "A request with this key is still executing. Retry with the same key and payload."
                        : "This key was already used with a different request payload.");
        this.inProgress = inProgress;
    }

    public boolean isInProgress() {
        return inProgress;
    }
}
