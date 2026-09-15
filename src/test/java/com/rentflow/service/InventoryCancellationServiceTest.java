package com.rentflow.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.rentflow.model.InventoryItem;
import com.rentflow.model.InventoryReservationCancellationOutcome;
import com.rentflow.model.InventoryReservationCancellationReceipt;
import com.rentflow.model.InventoryStatus;
import com.rentflow.model.InventoryStatusHistory;
import com.rentflow.model.ReservationCancellationEvent;
import com.rentflow.model.ReservationCancellationFailureCode;
import com.rentflow.model.ReservationCancellationProcessingOutcome;
import com.rentflow.model.ReservationCancellationRejectedException;
import com.rentflow.repository.InventoryRepository;
import com.rentflow.repository.InventoryStatusHistoryRepository;
import com.rentflow.repository.ReservationCancellationInboxRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryCancellationServiceTest {
    private static final UUID EVENT_ID = UUID.fromString("d880f919-2b5c-4f7e-a56d-e047e7d932a6");
    private static final String FINGERPRINT = "a".repeat(64);

    @Mock
    private InventoryRepository inventory;

    @Mock
    private InventoryStatusHistoryRepository history;

    @Mock
    private ReservationCancellationInboxRepository inbox;

    @Test
    void releasesAReservedItemAndPersistsHistoryAndReceiptBeforeFlushing() {
        InventoryItem item = new InventoryItem("DRILL-001", "Drill", "One", InventoryStatus.RESERVED);
        when(inbox.findById(EVENT_ID)).thenReturn(Optional.empty());
        when(inventory.findForUpdateBySerialNumber("DRILL-001")).thenReturn(Optional.of(item));
        InventoryService service = service();

        assertThat(service.releaseAfterCancellation(event()))
                .isEqualTo(ReservationCancellationProcessingOutcome.RELEASED);

        ArgumentCaptor<InventoryStatusHistory> historyCaptor = ArgumentCaptor.forClass(InventoryStatusHistory.class);
        ArgumentCaptor<InventoryReservationCancellationReceipt> receiptCaptor =
                ArgumentCaptor.forClass(InventoryReservationCancellationReceipt.class);
        InOrder order = inOrder(inbox, inventory, history);
        order.verify(inbox).acquireEventLock(42L);
        order.verify(inbox).findById(EVENT_ID);
        order.verify(inventory).findForUpdateBySerialNumber("DRILL-001");
        order.verify(history).save(historyCaptor.capture());
        order.verify(inbox).save(receiptCaptor.capture());
        order.verify(inbox).flush();
        assertThat(item.getStatus()).isEqualTo(InventoryStatus.AVAILABLE);
        assertThat(historyCaptor.getValue().getStatusFrom()).isEqualTo(InventoryStatus.RESERVED);
        assertThat(historyCaptor.getValue().getStatusTo()).isEqualTo(InventoryStatus.AVAILABLE);
        assertThat(receiptCaptor.getValue().getOutcome()).isEqualTo(InventoryReservationCancellationOutcome.RELEASED);
    }

    @Test
    void recordsAnAlreadyAvailableNoOpWithoutHistory() {
        InventoryItem item = new InventoryItem("DRILL-001", "Drill", "One", InventoryStatus.AVAILABLE);
        when(inbox.findById(EVENT_ID)).thenReturn(Optional.empty());
        when(inventory.findForUpdateBySerialNumber("DRILL-001")).thenReturn(Optional.of(item));

        assertThat(service().releaseAfterCancellation(event()))
                .isEqualTo(ReservationCancellationProcessingOutcome.ALREADY_AVAILABLE);

        ArgumentCaptor<InventoryReservationCancellationReceipt> receiptCaptor =
                ArgumentCaptor.forClass(InventoryReservationCancellationReceipt.class);
        verify(inbox).save(receiptCaptor.capture());
        verify(inbox).flush();
        verifyNoInteractions(history);
        assertThat(receiptCaptor.getValue().getOutcome())
                .isEqualTo(InventoryReservationCancellationOutcome.ALREADY_AVAILABLE);
    }

    @Test
    void matchingReceiptSuppressesEvaluationOfTheCurrentItem() {
        InventoryReservationCancellationReceipt receipt = new InventoryReservationCancellationReceipt(
                EVENT_ID,
                FINGERPRINT,
                "DRILL-001",
                Instant.parse("2026-09-15T15:30:00Z"),
                InventoryReservationCancellationOutcome.RELEASED);
        when(inbox.findById(EVENT_ID)).thenReturn(Optional.of(receipt));

        assertThat(service().releaseAfterCancellation(event()))
                .isEqualTo(ReservationCancellationProcessingOutcome.DUPLICATE);

        InOrder order = inOrder(inbox);
        order.verify(inbox).acquireEventLock(42L);
        order.verify(inbox).findById(EVENT_ID);
        verifyNoInteractions(inventory, history);
        verify(inbox, never()).save(org.mockito.ArgumentMatchers.any());
        verify(inbox, never()).flush();
    }

    @Test
    void rejectsEventIdentifierReuseBeforeLoadingInventory() {
        InventoryReservationCancellationReceipt receipt = new InventoryReservationCancellationReceipt(
                EVENT_ID,
                "b".repeat(64),
                "OTHER",
                Instant.parse("2026-09-15T15:30:00Z"),
                InventoryReservationCancellationOutcome.RELEASED);
        when(inbox.findById(EVENT_ID)).thenReturn(Optional.of(receipt));

        assertFailure(event(), ReservationCancellationFailureCode.EVENT_ID_REUSED);

        verifyNoInteractions(inventory, history);
        verify(inbox, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsMissingAndIncompatibleItemsWithoutAReceipt() {
        when(inbox.findById(EVENT_ID)).thenReturn(Optional.empty());
        when(inventory.findForUpdateBySerialNumber("DRILL-001")).thenReturn(Optional.empty());
        assertFailure(event(), ReservationCancellationFailureCode.INVENTORY_ITEM_NOT_FOUND);

        for (InventoryStatus status : new InventoryStatus[] {
            InventoryStatus.RENTED,
            InventoryStatus.INSPECTION_REQUIRED,
            InventoryStatus.UNDER_MAINTENANCE,
            InventoryStatus.RETIRED
        }) {
            org.mockito.Mockito.reset(inventory, history, inbox);
            when(inbox.findById(EVENT_ID)).thenReturn(Optional.empty());
            when(inventory.findForUpdateBySerialNumber("DRILL-001"))
                    .thenReturn(Optional.of(new InventoryItem("DRILL-001", "Drill", "One", status)));
            assertFailure(event(), ReservationCancellationFailureCode.INVALID_INVENTORY_STATUS);
            verifyNoInteractions(history);
            verify(inbox, never()).save(org.mockito.ArgumentMatchers.any());
        }
    }

    private InventoryService service() {
        return new InventoryService(inventory, history, inbox);
    }

    private ReservationCancellationEvent event() {
        return new ReservationCancellationEvent(
                EVENT_ID, Instant.parse("2026-09-15T15:30:00Z"), "DRILL-001", FINGERPRINT, 42L);
    }

    private void assertFailure(ReservationCancellationEvent event, ReservationCancellationFailureCode code) {
        assertThatThrownBy(() -> service().releaseAfterCancellation(event))
                .isInstanceOfSatisfying(ReservationCancellationRejectedException.class, exception -> {
                    assertThat(exception.getFailureCode()).isEqualTo(code);
                    assertThat(exception.getEventId()).contains(EVENT_ID);
                });
    }
}
