package com.rentflow.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.rentflow.model.InventoryStatusTransition;

final class IdempotencyFingerprint {
    private IdempotencyFingerprint() {}

    static String payload(List<InventoryStatusTransition> transitions) {
        MessageDigest digest = sha256();
        add(digest, "inventory-status-transition:payload:v1");
        for (InventoryStatusTransition transition : transitions) {
            add(digest, transition.serialNumber());
            add(digest, transition.status().name());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static long lockId(UUID key) {
        MessageDigest digest = sha256();
        add(digest, "inventory-status-transition:key:v1");
        add(digest, key.toString());
        return ByteBuffer.wrap(digest.digest()).getLong();
    }

    private static void add(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
