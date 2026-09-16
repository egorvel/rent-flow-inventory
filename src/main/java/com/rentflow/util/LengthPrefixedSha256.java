package com.rentflow.util;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class LengthPrefixedSha256 {
    private LengthPrefixedSha256() {}

    public static String hex(Iterable<String> values) {
        return HexFormat.of().formatHex(hash(values));
    }

    public static long firstLong(Iterable<String> values) {
        return ByteBuffer.wrap(hash(values)).getLong();
    }

    private static byte[] hash(Iterable<String> values) {
        MessageDigest digest = sha256();
        for (String value : values) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(
                    ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
        return digest.digest();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
