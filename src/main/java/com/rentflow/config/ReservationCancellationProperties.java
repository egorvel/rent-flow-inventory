package com.rentflow.config;

import java.time.Duration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("inventory.kafka.reservation-cancellation")
public record ReservationCancellationProperties(
        @NotBlank String topic,
        @NotBlank String deadLetterTopic,
        @Positive int maxPayloadBytes,
        @Valid @NotNull Retry retry) {

    public record Retry(
            @Min(1) @Max(5) int maxAttempts,
            @NotNull Duration initialBackoff,
            @Min(1) double multiplier,
            @NotNull Duration maxBackoff) {

        public Retry {
            if (initialBackoff != null && initialBackoff.isNegative()) {
                throw new IllegalArgumentException("initialBackoff must not be negative");
            }
            if (maxBackoff != null && maxBackoff.isNegative()) {
                throw new IllegalArgumentException("maxBackoff must not be negative");
            }
            if (initialBackoff != null && maxBackoff != null && maxBackoff.compareTo(initialBackoff) < 0) {
                throw new IllegalArgumentException("maxBackoff must not be less than initialBackoff");
            }
        }
    }
}
