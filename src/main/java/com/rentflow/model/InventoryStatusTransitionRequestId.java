package com.rentflow.model;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class InventoryStatusTransitionRequestId implements Serializable {
    @Column(name = "caller_scope", nullable = false, length = 80)
    private String callerScope;

    @Column(name = "idempotency_key", nullable = false)
    private UUID key;

    protected InventoryStatusTransitionRequestId() {}

    public InventoryStatusTransitionRequestId(String callerScope, UUID key) {
        this.callerScope = Objects.requireNonNull(callerScope);
        this.key = Objects.requireNonNull(key);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof InventoryStatusTransitionRequestId id
                && callerScope.equals(id.callerScope)
                && key.equals(id.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(callerScope, key);
    }
}
