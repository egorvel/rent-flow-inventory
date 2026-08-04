package com.rentflow.model;

public enum InventoryStatus {
    AVAILABLE,
    RESERVED,
    RENTED,
    INSPECTION_REQUIRED,
    UNDER_MAINTENANCE,
    RETIRED;

    public boolean canTransitionTo(InventoryStatus target) {
        if (target == null || target == this) {
            return false;
        }
        return switch (this) {
            case AVAILABLE -> true;
            case RESERVED -> target == AVAILABLE || target == RENTED;
            case RENTED -> target == INSPECTION_REQUIRED;
            case INSPECTION_REQUIRED -> target == AVAILABLE || target == UNDER_MAINTENANCE || target == RETIRED;
            case UNDER_MAINTENANCE -> target == AVAILABLE || target == RETIRED;
            case RETIRED -> false;
        };
    }
}
