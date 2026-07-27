package com.rentflow.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;

@Entity
@Table(name = "inventory_items", schema = "inventory")
public class InventoryItem {

    @Id
    @Column(name = "serial_number", nullable = false, updatable = false, length = 64)
    private String serialNumber;

    @Column(name = "type", nullable = false, length = 100)
    private String type;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private InventoryStatus status;

    protected InventoryItem() {}

    public InventoryItem(String serialNumber, String type, String name, InventoryStatus status) {
        this.serialNumber = Objects.requireNonNull(serialNumber, "serialNumber must not be null");
        replaceDetails(type, name, status);
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public String getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public InventoryStatus getStatus() {
        return status;
    }

    public void replaceDetails(String type, String name, InventoryStatus status) {
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof InventoryItem that)) {
            return false;
        }
        return serialNumber.equals(that.serialNumber);
    }

    @Override
    public int hashCode() {
        return serialNumber.hashCode();
    }
}
