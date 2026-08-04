package com.rentflow.model;

import java.time.Instant;
import java.util.Objects;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "inventory_status_history", schema = "inventory")
public class InventoryStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "serial_number", nullable = false, updatable = false, length = 64)
    private String serialNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_from", nullable = false, updatable = false, length = 32)
    private InventoryStatus statusFrom;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_to", nullable = false, updatable = false, length = 32)
    private InventoryStatus statusTo;

    @Column(name = "transitioned_at", nullable = false, insertable = false, updatable = false)
    private Instant timestamp;

    protected InventoryStatusHistory() {}

    public InventoryStatusHistory(String serialNumber, InventoryStatus statusFrom, InventoryStatus statusTo) {
        this.serialNumber = Objects.requireNonNull(serialNumber, "serialNumber must not be null");
        this.statusFrom = Objects.requireNonNull(statusFrom, "statusFrom must not be null");
        this.statusTo = Objects.requireNonNull(statusTo, "statusTo must not be null");
        if (statusFrom == statusTo) {
            throw new IllegalArgumentException("statusFrom and statusTo must differ");
        }
    }

    public Long getId() {
        return id;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public InventoryStatus getStatusFrom() {
        return statusFrom;
    }

    public InventoryStatus getStatusTo() {
        return statusTo;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
