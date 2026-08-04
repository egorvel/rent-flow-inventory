package com.rentflow.service;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.rentflow.model.InventoryItem;
import com.rentflow.model.InventoryStatus;
import com.rentflow.model.InventoryStatusHistory;
import com.rentflow.repository.InventoryRepository;
import com.rentflow.repository.InventoryStatusHistoryRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock
    private InventoryRepository repository;

    @Mock
    private InventoryStatusHistoryRepository historyRepository;

    @Test
    void createsAndFlushesAnItemWithTheAssignedSerialNumber() {
        when(repository.existsById("DRILL-001")).thenReturn(false);
        when(repository.saveAndFlush(org.mockito.ArgumentMatchers.any(InventoryItem.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        InventoryService service = new InventoryService(repository, historyRepository);

        InventoryItem item = new InventoryItem("DRILL-001", "Industrial drill", "Bosch", InventoryStatus.AVAILABLE);
        InventoryItem created = service.create(item);

        ArgumentCaptor<InventoryItem> captor = ArgumentCaptor.forClass(InventoryItem.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(created).isSameAs(captor.getValue());
        assertThat(created.getSerialNumber()).isEqualTo("DRILL-001");
    }

    @Test
    void rejectsAnExistingSerialBeforeSaving() {
        when(repository.existsById("DRILL-001")).thenReturn(true);
        InventoryService service = new InventoryService(repository, historyRepository);

        InventoryItem item = new InventoryItem("DRILL-001", "Drill", "Bosch", InventoryStatus.AVAILABLE);
        assertThatThrownBy(() -> service.create(item)).isInstanceOf(InventoryItemAlreadyExistsException.class);

        verify(repository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void translatesAConcurrentPrimaryKeyFailureToConflict() {
        when(repository.existsById("DRILL-001")).thenReturn(false);
        when(repository.saveAndFlush(org.mockito.ArgumentMatchers.any(InventoryItem.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));
        InventoryService service = new InventoryService(repository, historyRepository);

        InventoryItem item = new InventoryItem("DRILL-001", "Drill", "Bosch", InventoryStatus.AVAILABLE);
        assertThatThrownBy(() -> service.create(item)).isInstanceOf(InventoryItemAlreadyExistsException.class);
    }

    @Test
    void returnsAnExistingItem() {
        InventoryItem item = new InventoryItem("DRILL-001", "Drill", "Bosch", InventoryStatus.AVAILABLE);
        when(repository.findById("DRILL-001")).thenReturn(Optional.of(item));
        InventoryService service = new InventoryService(repository, historyRepository);

        assertThat(service.get("DRILL-001")).isSameAs(item);
    }

    @Test
    void rejectsAMissingItemWithoutMutation() {
        when(repository.findById("MISSING")).thenReturn(Optional.empty());
        InventoryService service = new InventoryService(repository, historyRepository);

        assertThatThrownBy(() -> service.get("MISSING")).isInstanceOf(InventoryItemNotFoundException.class);

        verify(repository).findById("MISSING");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void replacesOnlyMutableDetailsOnAnExistingItem() {
        InventoryItem item = new InventoryItem("DRILL-001", "Drill", "Original", InventoryStatus.AVAILABLE);
        when(repository.findById("DRILL-001")).thenReturn(Optional.of(item));
        InventoryService service = new InventoryService(repository, historyRepository);

        InventoryItem toReplace =
                new InventoryItem("DRILL-001", "Industrial drill", "Updated", InventoryStatus.UNDER_MAINTENANCE);
        InventoryItem replaced = service.replace(toReplace);

        assertThat(replaced).isSameAs(item);
        assertThat(item.getSerialNumber()).isEqualTo("DRILL-001");
        assertThat(item.getType()).isEqualTo("Industrial drill");
        assertThat(item.getName()).isEqualTo("Updated");
        assertThat(item.getStatus()).isEqualTo(InventoryStatus.UNDER_MAINTENANCE);
        verify(repository).findById("DRILL-001");
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(historyRepository);
    }

    @Test
    void rejectsReplacementOfAMissingItemWithoutMutation() {
        when(repository.findById("MISSING")).thenReturn(Optional.empty());
        InventoryService service = new InventoryService(repository, historyRepository);

        InventoryItem item = new InventoryItem("MISSING", "Drill", "Updated", InventoryStatus.AVAILABLE);
        assertThatThrownBy(() -> service.replace(item)).isInstanceOf(InventoryItemNotFoundException.class);

        verify(repository).findById("MISSING");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void loadsAnExistingItemBeforeDeletingIt() {
        InventoryItem item = new InventoryItem("DRILL-001", "Drill", "Original", InventoryStatus.RETIRED);
        when(repository.findById("DRILL-001")).thenReturn(Optional.of(item));
        InventoryService service = new InventoryService(repository, historyRepository);

        service.delete("DRILL-001");

        InOrder inOrder = org.mockito.Mockito.inOrder(repository);
        inOrder.verify(repository).findById("DRILL-001");
        inOrder.verify(repository).delete(item);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void rejectsDeletionOfAMissingItemWithoutMutation() {
        when(repository.findById("MISSING")).thenReturn(Optional.empty());
        InventoryService service = new InventoryService(repository, historyRepository);

        assertThatThrownBy(() -> service.delete("MISSING")).isInstanceOf(InventoryItemNotFoundException.class);

        verify(repository).findById("MISSING");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void transitionsALockedItemAndSavesTheCapturedStatusChange() {
        InventoryItem item = new InventoryItem("DRILL-001", "Drill", "Original", InventoryStatus.RESERVED);
        when(repository.findForUpdateBySerialNumber("DRILL-001")).thenReturn(Optional.of(item));
        InventoryService service = new InventoryService(repository, historyRepository);

        service.transitionStatus("DRILL-001", InventoryStatus.RENTED);

        assertThat(item.getStatus()).isEqualTo(InventoryStatus.RENTED);
        ArgumentCaptor<InventoryStatusHistory> historyCaptor = ArgumentCaptor.forClass(InventoryStatusHistory.class);
        verify(historyRepository).save(historyCaptor.capture());
        InventoryStatusHistory history = historyCaptor.getValue();
        assertThat(history.getSerialNumber()).isEqualTo("DRILL-001");
        assertThat(history.getStatusFrom()).isEqualTo(InventoryStatus.RESERVED);
        assertThat(history.getStatusTo()).isEqualTo(InventoryStatus.RENTED);
        verify(repository).findForUpdateBySerialNumber("DRILL-001");
    }

    @Test
    void rejectsMissingAndInvalidTransitionsWithoutMutationOrHistory() {
        when(repository.findForUpdateBySerialNumber("MISSING")).thenReturn(Optional.empty());
        InventoryService service = new InventoryService(repository, historyRepository);

        assertThatThrownBy(() -> service.transitionStatus("MISSING", InventoryStatus.AVAILABLE))
                .isInstanceOf(InventoryItemNotFoundException.class);

        InventoryItem rented = new InventoryItem("DRILL-001", "Drill", "Original", InventoryStatus.RENTED);
        when(repository.findForUpdateBySerialNumber("DRILL-001")).thenReturn(Optional.of(rented));
        assertThatThrownBy(() -> service.transitionStatus("DRILL-001", InventoryStatus.AVAILABLE))
                .isInstanceOf(InvalidInventoryStatusTransitionException.class);
        assertThatThrownBy(() -> service.transitionStatus("DRILL-001", InventoryStatus.RENTED))
                .isInstanceOf(InvalidInventoryStatusTransitionException.class);
        assertThatThrownBy(() -> service.transitionStatus("DRILL-001", null))
                .isInstanceOf(InvalidInventoryStatusTransitionException.class);

        assertThat(rented.getStatus()).isEqualTo(InventoryStatus.RENTED);
        verifyNoInteractions(historyRepository);
    }

    @Test
    void delegatesHistoryFilteringPagingAndEveryDeterministicSort() {
        when(historyRepository.findAll(eq("DRILL"), any(Pageable.class))).thenReturn(Page.empty());
        InventoryService service = new InventoryService(repository, historyRepository);

        for (InventoryStatusHistorySortField field : InventoryStatusHistorySortField.values()) {
            service.listStatusHistory(1, 7, "DRILL", field, Sort.Direction.DESC);
        }

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(historyRepository, times(InventoryStatusHistorySortField.values().length))
                .findAll(eq("DRILL"), pageableCaptor.capture());
        List<Pageable> pageables = pageableCaptor.getAllValues();
        for (int index = 0; index < pageables.size(); index++) {
            Pageable pageable = pageables.get(index);
            InventoryStatusHistorySortField field = InventoryStatusHistorySortField.values()[index];
            assertThat(pageable.getPageNumber()).isEqualTo(1);
            assertThat(pageable.getPageSize()).isEqualTo(7);
            assertThat(pageable.getSort().toList())
                    .extracting(Sort.Order::getProperty, Sort.Order::getDirection)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(field.property(), Sort.Direction.DESC),
                            org.assertj.core.groups.Tuple.tuple("id", Sort.Direction.DESC));
        }
    }
}
