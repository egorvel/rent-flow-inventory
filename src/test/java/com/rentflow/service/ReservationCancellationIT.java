package com.rentflow.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import com.rentflow.model.InventoryItem;
import com.rentflow.model.InventoryReservationCancellationOutcome;
import com.rentflow.model.InventoryStatus;
import com.rentflow.model.InventoryStatusTransition;
import com.rentflow.model.ReservationCancellationEvent;
import com.rentflow.model.ReservationCancellationFailureCode;
import com.rentflow.model.ReservationCancellationProcessingOutcome;
import com.rentflow.model.ReservationCancellationRejectedException;
import com.rentflow.repository.InventoryRepository;
import com.rentflow.repository.InventoryStatusHistoryRepository;
import com.rentflow.repository.ReservationCancellationInboxRepository;
import com.rentflow.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.kafka.listener.auto-startup=false")
class ReservationCancellationIT extends PostgresIntegrationTest {
    private static final UUID EVENT_ID = UUID.fromString("d880f919-2b5c-4f7e-a56d-e047e7d932a6");
    private static final String FINGERPRINT = "a".repeat(64);

    @Autowired
    private InventoryService service;

    @Autowired
    private InventoryRepository inventory;

    @Autowired
    private InventoryStatusHistoryRepository history;

    @Autowired
    private ReservationCancellationInboxRepository inbox;

    @BeforeEach
    void reset() {
        inbox.deleteAllInBatch();
        history.deleteAllInBatch();
        inventory.deleteAllInBatch();
    }

    @Test
    void commitsReleaseHistoryAndReceiptTogether() {
        inventory.saveAndFlush(new InventoryItem("DRILL-001", "Drill", "One", InventoryStatus.RESERVED));

        assertThat(service.releaseAfterCancellation(event(EVENT_ID, FINGERPRINT)))
                .isEqualTo(ReservationCancellationProcessingOutcome.RELEASED);

        assertThat(inventory.findById("DRILL-001").orElseThrow().getStatus()).isEqualTo(InventoryStatus.AVAILABLE);
        assertThat(history.findAll()).singleElement().satisfies(entry -> {
            assertThat(entry.getStatusFrom()).isEqualTo(InventoryStatus.RESERVED);
            assertThat(entry.getStatusTo()).isEqualTo(InventoryStatus.AVAILABLE);
        });
        assertThat(inbox.findById(EVENT_ID)).get().satisfies(receipt -> {
            assertThat(receipt.getFingerprint()).isEqualTo(FINGERPRINT);
            assertThat(receipt.getOutcome()).isEqualTo(InventoryReservationCancellationOutcome.RELEASED);
            assertThat(receipt.getProcessedAt()).isNotNull();
        });
    }

    @Test
    void matchingDuplicateDoesNotReleaseALaterReservation() {
        inventory.saveAndFlush(new InventoryItem("DRILL-001", "Drill", "One", InventoryStatus.RESERVED));
        ReservationCancellationEvent event = event(EVENT_ID, FINGERPRINT);
        service.releaseAfterCancellation(event);
        assertThat(service.transitionStatus(
                                List.of(new InventoryStatusTransition("DRILL-001", InventoryStatus.RESERVED)))
                        .status())
                .isEqualTo(204);

        assertThat(service.releaseAfterCancellation(event))
                .isEqualTo(ReservationCancellationProcessingOutcome.DUPLICATE);

        assertThat(inventory.findById("DRILL-001").orElseThrow().getStatus()).isEqualTo(InventoryStatus.RESERVED);
        assertThat(history.count()).isEqualTo(2);
        assertThat(inbox.count()).isOne();
    }

    @Test
    void availableItemCreatesOnlyANoopReceipt() {
        inventory.saveAndFlush(new InventoryItem("DRILL-001", "Drill", "One", InventoryStatus.AVAILABLE));

        assertThat(service.releaseAfterCancellation(event(EVENT_ID, FINGERPRINT)))
                .isEqualTo(ReservationCancellationProcessingOutcome.ALREADY_AVAILABLE);

        assertThat(history.count()).isZero();
        assertThat(inbox.findById(EVENT_ID).orElseThrow().getOutcome())
                .isEqualTo(InventoryReservationCancellationOutcome.ALREADY_AVAILABLE);
    }

    @Test
    void receiptConstraintFailureRollsBackTheItemAndHistory() {
        inventory.saveAndFlush(new InventoryItem("DRILL-001", "Drill", "One", InventoryStatus.RESERVED));

        assertThatThrownBy(() -> service.releaseAfterCancellation(event(EVENT_ID, "invalid")))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(inventory.findById("DRILL-001").orElseThrow().getStatus()).isEqualTo(InventoryStatus.RESERVED);
        assertThat(history.count()).isZero();
        assertThat(inbox.count()).isZero();
    }

    @Test
    void conflictingIdentifierAndBusinessFailuresLeaveNoAdditionalState() {
        inventory.saveAndFlush(new InventoryItem("DRILL-001", "Drill", "One", InventoryStatus.AVAILABLE));
        service.releaseAfterCancellation(event(EVENT_ID, FINGERPRINT));

        assertFailure(event(EVENT_ID, "b".repeat(64)), ReservationCancellationFailureCode.EVENT_ID_REUSED);
        assertThat(inbox.count()).isOne();
        assertThat(history.count()).isZero();

        assertFailure(
                new ReservationCancellationEvent(
                        UUID.randomUUID(), Instant.parse("2026-09-15T15:30:00Z"), "MISSING", "c".repeat(64), 43L),
                ReservationCancellationFailureCode.INVENTORY_ITEM_NOT_FOUND);
        assertThat(inbox.count()).isOne();
    }

    private ReservationCancellationEvent event(UUID eventId, String fingerprint) {
        return new ReservationCancellationEvent(
                eventId, Instant.parse("2026-09-15T15:30:00Z"), "DRILL-001", fingerprint, 42L);
    }

    private void assertFailure(ReservationCancellationEvent event, ReservationCancellationFailureCode code) {
        assertThatThrownBy(() -> service.releaseAfterCancellation(event))
                .isInstanceOfSatisfying(
                        ReservationCancellationRejectedException.class,
                        exception -> assertThat(exception.getFailureCode()).isEqualTo(code));
    }
}
