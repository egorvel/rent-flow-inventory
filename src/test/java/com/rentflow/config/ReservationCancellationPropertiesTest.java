package com.rentflow.config;

import java.time.Duration;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationCancellationPropertiesTest {
    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsTheDocumentedDefaults() {
        ReservationCancellationProperties properties = properties(5, Duration.ofSeconds(1), 2, Duration.ofSeconds(8));

        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    void rejectsInvalidAttemptAndBackoffBounds() {
        assertThat(validator.validate(properties(0, Duration.ZERO, 2, Duration.ofSeconds(8))))
                .isNotEmpty();
        assertThat(validator.validate(properties(6, Duration.ZERO, 2, Duration.ofSeconds(8))))
                .isNotEmpty();
        assertThat(validator.validate(properties(5, Duration.ZERO, 0.5, Duration.ofSeconds(8))))
                .isNotEmpty();
        assertThatThrownBy(() -> properties(5, Duration.ofSeconds(-1), 2, Duration.ofSeconds(8)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(5, Duration.ofSeconds(2), 2, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ReservationCancellationProperties properties(
            int attempts, Duration initial, double multiplier, Duration maximum) {
        return new ReservationCancellationProperties(
                "rentflow.reservation.cancelled.v1",
                "rentflow.inventory.reservation-cancellation.dlt.v1",
                4096,
                new ReservationCancellationProperties.Retry(attempts, initial, multiplier, maximum));
    }
}
