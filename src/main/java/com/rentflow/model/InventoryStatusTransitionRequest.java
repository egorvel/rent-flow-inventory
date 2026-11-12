package com.rentflow.model;

import java.time.Duration;
import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "inventory_status_transition_requests", schema = "inventory")
public class InventoryStatusTransitionRequest {
    @EmbeddedId
    private InventoryStatusTransitionRequestId id;

    @Column(nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "http_status", nullable = false)
    private int httpStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private InventoryStatusTransitionOutcome outcome;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected InventoryStatusTransitionRequest() {}

    public InventoryStatusTransitionRequest(InventoryStatusTransitionRequestId id) {
        this.id = id;
    }

    public void complete(String fingerprint, InventoryStatusTransitionOutcome outcome, Instant databaseTime) {
        this.fingerprint = fingerprint;
        this.outcome = outcome;
        this.httpStatus = outcome.status();
        this.recordedAt = databaseTime;
        this.expiresAt = databaseTime.plus(Duration.ofDays(7));
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public InventoryStatusTransitionOutcome getOutcome() {
        return outcome;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
