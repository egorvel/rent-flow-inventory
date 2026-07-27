package com.rentflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.rentflow.model.InventoryItem;
import com.rentflow.model.InventoryStatus;
import com.rentflow.repository.InventoryRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock
    private InventoryRepository repository;

    @Test
    void createsAndFlushesAnItemWithTheAssignedSerialNumber() {
        when(repository.existsById("DRILL-001")).thenReturn(false);
        when(repository.saveAndFlush(org.mockito.ArgumentMatchers.any(InventoryItem.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        var service = new InventoryService(repository);

        var created = service.create("DRILL-001", "Industrial drill", "Bosch", InventoryStatus.AVAILABLE);

        var captor = ArgumentCaptor.forClass(InventoryItem.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(created).isSameAs(captor.getValue());
        assertThat(created.getSerialNumber()).isEqualTo("DRILL-001");
    }

    @Test
    void rejectsAnExistingSerialBeforeSaving() {
        when(repository.existsById("DRILL-001")).thenReturn(true);
        var service = new InventoryService(repository);

        assertThatThrownBy(() -> service.create("DRILL-001", "Drill", "Bosch", InventoryStatus.AVAILABLE))
                .isInstanceOf(InventoryItemAlreadyExistsException.class);

        verify(repository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void translatesAConcurrentPrimaryKeyFailureToConflict() {
        when(repository.existsById("DRILL-001")).thenReturn(false);
        when(repository.saveAndFlush(org.mockito.ArgumentMatchers.any(InventoryItem.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));
        var service = new InventoryService(repository);

        assertThatThrownBy(() -> service.create("DRILL-001", "Drill", "Bosch", InventoryStatus.AVAILABLE))
                .isInstanceOf(InventoryItemAlreadyExistsException.class);
    }

    @Test
    void returnsAnExistingItem() {
        var item = new InventoryItem("DRILL-001", "Drill", "Bosch", InventoryStatus.AVAILABLE);
        when(repository.findById("DRILL-001")).thenReturn(Optional.of(item));
        var service = new InventoryService(repository);

        assertThat(service.get("DRILL-001")).isSameAs(item);
    }

    @Test
    void rejectsAMissingItemWithoutMutation() {
        when(repository.findById("MISSING")).thenReturn(Optional.empty());
        var service = new InventoryService(repository);

        assertThatThrownBy(() -> service.get("MISSING")).isInstanceOf(InventoryItemNotFoundException.class);

        verify(repository).findById("MISSING");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void replacesOnlyMutableDetailsOnAnExistingItem() {
        var item = new InventoryItem("DRILL-001", "Drill", "Original", InventoryStatus.AVAILABLE);
        when(repository.findById("DRILL-001")).thenReturn(Optional.of(item));
        var service = new InventoryService(repository);

        var replaced = service.replace("DRILL-001", "Industrial drill", "Updated", InventoryStatus.UNDER_MAINTENANCE);

        assertThat(replaced).isSameAs(item);
        assertThat(item.getSerialNumber()).isEqualTo("DRILL-001");
        assertThat(item.getType()).isEqualTo("Industrial drill");
        assertThat(item.getName()).isEqualTo("Updated");
        assertThat(item.getStatus()).isEqualTo(InventoryStatus.UNDER_MAINTENANCE);
        verify(repository).findById("DRILL-001");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void rejectsReplacementOfAMissingItemWithoutMutation() {
        when(repository.findById("MISSING")).thenReturn(Optional.empty());
        var service = new InventoryService(repository);

        assertThatThrownBy(() -> service.replace("MISSING", "Drill", "Updated", InventoryStatus.AVAILABLE))
                .isInstanceOf(InventoryItemNotFoundException.class);

        verify(repository).findById("MISSING");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void loadsAnExistingItemBeforeDeletingIt() {
        var item = new InventoryItem("DRILL-001", "Drill", "Original", InventoryStatus.RETIRED);
        when(repository.findById("DRILL-001")).thenReturn(Optional.of(item));
        var service = new InventoryService(repository);

        service.delete("DRILL-001");

        var inOrder = org.mockito.Mockito.inOrder(repository);
        inOrder.verify(repository).findById("DRILL-001");
        inOrder.verify(repository).delete(item);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void rejectsDeletionOfAMissingItemWithoutMutation() {
        when(repository.findById("MISSING")).thenReturn(Optional.empty());
        var service = new InventoryService(repository);

        assertThatThrownBy(() -> service.delete("MISSING")).isInstanceOf(InventoryItemNotFoundException.class);

        verify(repository).findById("MISSING");
        verifyNoMoreInteractions(repository);
    }
}
