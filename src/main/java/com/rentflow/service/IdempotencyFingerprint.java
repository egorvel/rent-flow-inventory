package com.rentflow.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.rentflow.model.InventoryStatusTransition;
import com.rentflow.util.LengthPrefixedSha256;

final class IdempotencyFingerprint {
    private IdempotencyFingerprint() {}

    static String payload(List<InventoryStatusTransition> transitions) {
        List<String> values = new ArrayList<>();
        values.add("inventory-status-transition:payload:v1");
        for (InventoryStatusTransition transition : transitions) {
            values.add(transition.serialNumber());
            values.add(transition.status().name());
        }
        return LengthPrefixedSha256.hex(values);
    }

    static long lockId(UUID key) {
        return LengthPrefixedSha256.firstLong(List.of("inventory-status-transition:key:v1", key.toString()));
    }
}
