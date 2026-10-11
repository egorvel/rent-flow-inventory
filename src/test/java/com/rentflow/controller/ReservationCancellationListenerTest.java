package com.rentflow.controller;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.rentflow.converter.ReservationCancellationEventConverter;
import com.rentflow.model.ReservationCancellationEvent;
import com.rentflow.model.ReservationCancellationProcessingOutcome;
import com.rentflow.service.InventoryService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class ReservationCancellationListenerTest {
    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");

    @Test
    void measuresOnlyCommittedNonDuplicateResultsAndDiagnosesClockSkew(CapturedOutput output) {
        ReservationCancellationEventConverter converter = mock(ReservationCancellationEventConverter.class);
        InventoryService service = mock(InventoryService.class);
        ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>("source", 0, 5, new byte[0], new byte[0]);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        try {
            ReservationCancellationListener listener =
                    new ReservationCancellationListener(converter, service, metrics, Clock.fixed(NOW, ZoneOffset.UTC));
            ReservationCancellationEvent event = event(NOW.minusSeconds(12));
            when(converter.convert(record)).thenReturn(event);
            when(service.releaseAfterCancellation(event))
                    .thenReturn(
                            ReservationCancellationProcessingOutcome.RELEASED,
                            ReservationCancellationProcessingOutcome.ALREADY_AVAILABLE,
                            ReservationCancellationProcessingOutcome.DUPLICATE);
            listener.onCancellation(record);
            listener.onCancellation(record);
            listener.onCancellation(record);
            assertThat(metrics.get("inventory.reservation.cancellation.completion.delay")
                            .timer()
                            .count())
                    .isEqualTo(2);
            assertThat(metrics.get("inventory.reservation.cancellation.completion.delay")
                            .timer()
                            .totalTime(java.util.concurrent.TimeUnit.SECONDS))
                    .isEqualTo(24);
            ReservationCancellationEvent future = event(NOW.plusSeconds(1));
            when(converter.convert(record)).thenReturn(future);
            when(service.releaseAfterCancellation(future))
                    .thenReturn(ReservationCancellationProcessingOutcome.RELEASED);
            listener.onCancellation(record);
            assertThat(metrics.get("inventory.reservation.cancellation.clock.skew")
                            .counter()
                            .count())
                    .isOne();
            assertThat(metrics.get("inventory.reservation.cancellation.completion.delay")
                            .timer()
                            .count())
                    .isEqualTo(2);
            when(service.releaseAfterCancellation(future))
                    .thenThrow(new IllegalStateException("synthetic-private-value"));
            assertThatThrownBy(() -> listener.onCancellation(record)).isInstanceOf(RuntimeException.class);
            assertThat(metrics.get("inventory.reservation.cancellation.completion.delay")
                            .timer()
                            .count())
                    .isEqualTo(2);
            assertThat(metrics.get("inventory.reservation.cancellation.records")
                            .tag("outcome", "received")
                            .counter()
                            .count())
                    .isEqualTo(5);
            assertThat(output.getOut().split("Reservation cancellation processed", -1))
                    .hasSize(5);
            assertThat(output).contains("SERIAL").doesNotContain("synthetic-private-value", "fingerprint");
        } finally {
            metrics.close();
        }
    }

    private ReservationCancellationEvent event(Instant occurredAt) {
        return new ReservationCancellationEvent(UUID.randomUUID(), occurredAt, "SERIAL", "fingerprint", 1);
    }
}
