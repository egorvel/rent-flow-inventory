package com.rentflow.util;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LengthPrefixedSha256Test {
    @Test
    void hashesOrderedUtf8ValuesWithUnambiguousLengthPrefixes() {
        assertThat(LengthPrefixedSha256.hex(List.of("ab", "c")))
                .isNotEqualTo(LengthPrefixedSha256.hex(List.of("a", "bc")))
                .isNotEqualTo(LengthPrefixedSha256.hex(List.of("c", "ab")))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void derivesTheLockIdFromTheFirstEightHashBytes() {
        List<String> values = List.of("namespace", "value");

        assertThat(LengthPrefixedSha256.firstLong(values)).isEqualTo(-5305971302849957975L);
        assertThat(LengthPrefixedSha256.hex(values))
                .isEqualTo("b65d673659362fa97db3bf0f0c2f082dcfdaaed6f42cbecf13ee42b349925fc3");
    }
}
