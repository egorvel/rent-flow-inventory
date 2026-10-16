package com.rentflow.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
import com.rentflow.model.InventoryStatusTransition;
import com.rentflow.model.InventoryStatusTransitionOutcome;
import com.rentflow.repository.InventoryRepository;
import com.rentflow.repository.InventoryStatusHistoryRepository;
import com.rentflow.repository.ReservationCancellationInboxRepository;

import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;

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

    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();

    @AfterEach
    void closeMetrics() {
        metrics.close();
    }

    @Mock
    private InventoryRepository repository;

    @Mock
    private InventoryStatusHistoryRepository historyRepository;

    @Mock
    private ReservationCancellationInboxRepository cancellationInboxRepository;

    @Test
    void createsAndFlushesAnItemWithTheAssignedSerialNumber() {
        when(repository.existsById("DRILL-001")).thenReturn(false);
        when(repository.saveAndFlush(org.mockito.ArgumentMatchers.any(InventoryItem.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        InventoryService service = new InventoryService(
                repository, historyRepository, cancellationInboxRepository, ObservationRegistry.NOOP, metrics);

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
        InventoryService service = new InventoryService(
                repository, historyRepository, cancellationInboxRepository, ObservationRegistry.NOOP, metrics);

        InventoryItem item = new InventoryItem("DRILL-001", "Drill", "Bosch", InventoryStatus.AVAILABLE);
        assertThatThrownBy(() -> service.create(item)).isInstanceOf(InventoryItemAlreadyExistsException.class);

        verify(repository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void translatesAConcurrentPrimaryKeyFailureToConflict() {
        when(repository.existsById("DRILL-001")).thenReturn(false);
        when(repository.saveAndFlush(org.mockito.ArgumentMatchers.any(InventoryItem.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));
        InventoryService service = new InventoryService(
                repository, historyRepository, cancellationInboxRepository, ObservationRegistry.NOOP, metrics);

        InventoryItem item = new InventoryItem("DRILL-001", "Drill", "Bosch", InventoryStatus.AVAILABLE);
        assertThatThrownBy(() -> service.create(item)).isInstanceOf(InventoryItemAlreadyExistsException.class);
    }

    @Test
    void returnsAnExistingItem() {
        InventoryItem item = new InventoryItem("DRILL-001", "Drill", "Bosch", InventoryStatus.AVAILABLE);
        when(repository.findById("DRILL-001")).thenReturn(Optional.of(item));
        InventoryService service = new InventoryService(
                repository, historyRepository, cancellationInboxRepository, ObservationRegistry.NOOP, metrics);

        assertThat(service.get("DRILL-001")).isSameAs(item);
    }

    @Test
    void rejectsAMissingItemWithoutMutation() {
        when(repository.findById("MISSING")).thenReturn(Optional.empty());
        InventoryService service = new InventoryService(
                repository, historyRepository, cancellationInboxRepository, ObservationRegistry.NOOP, metrics);

        assertThatThrownBy(() -> service.get("MISSING")).isInstanceOf(InventoryItemNotFoundException.class);

        verify(repository).findById("MISSING");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void replacesOnlyMutableDetailsOnAnExistingItem() {
        InventoryItem item = new InventoryItem("DRILL-001", "Drill", "Original", InventoryStatus.AVAILABLE);
        when(repository.findById("DRILL-001")).thenReturn(Optional.of(item));
        InventoryService service = new InventoryService(
                repository, historyRepository, cancellationInboxRepository, ObservationRegistry.NOOP, metrics);

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
        InventoryService service = new InventoryService(
                repository, historyRepository, cancellationInboxRepository, ObservationRegistry.NOOP, metrics);

        InventoryItem item = new InventoryItem("MISSING", "Drill", "Updated", InventoryStatus.AVAILABLE);
        assertThatThrownBy(() -> service.replace(item)).isInstanceOf(InventoryItemNotFoundException.class);

        verify(repository).findById("MISSING");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void loadsAnExistingItemBeforeDeletingIt() {
        InventoryItem item = new InventoryItem("DRILL-001", "Drill", "Original", InventoryStatus.RETIRED);
        when(repository.findById("DRILL-001")).thenReturn(Optional.of(item));
        InventoryService service = new InventoryService(
                repository, historyRepository, cancellationInboxRepository, ObservationRegistry.NOOP, metrics);

        service.delete("DRILL-001");

        InOrder inOrder = org.mockito.Mockito.inOrder(repository);
        inOrder.verify(repository).findById("DRILL-001");
        inOrder.verify(repository).delete(item);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void rejectsDeletionOfAMissingItemWithoutMutation() {
        when(repository.findById("MISSING")).thenReturn(Optional.empty());
        InventoryService service = new InventoryService(
                repository, historyRepository, cancellationInboxRepository, ObservationRegistry.NOOP, metrics);

        assertThatThrownBy(() -> service.delete("MISSING")).isInstanceOf(InventoryItemNotFoundException.class);

        verify(repository).findById("MISSING");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void transitionsALockedItemAndSavesTheCapturedStatusChange() {
        InventoryItem item = new InventoryItem("DRILL-001", "Drill", "Original", InventoryStatus.RESERVED);
        when(repository.findForUpdateBySerialNumber("DRILL-001")).thenReturn(Optional.of(item));
        InventoryService service = new InventoryService(
                repository, historyRepository, cancellationInboxRepository, ObservationRegistry.NOOP, metrics);

        service.transitionStatus(List.of(new InventoryStatusTransition("DRILL-001", InventoryStatus.RENTED)));

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
        InventoryService service = new InventoryService(
                repository, historyRepository, cancellationInboxRepository, ObservationRegistry.NOOP, metrics);

        assertThat(service.transitionStatus(
                                List.of(new InventoryStatusTransition("MISSING", InventoryStatus.AVAILABLE)))
                        .status())
                .isEqualTo(404);

        InventoryItem rented = new InventoryItem("DRILL-001", "Drill", "Original", InventoryStatus.RENTED);
        when(repository.findForUpdateBySerialNumber("DRILL-001")).thenReturn(Optional.of(rented));
        assertThat(service.transitionStatus(
                                List.of(new InventoryStatusTransition("DRILL-001", InventoryStatus.AVAILABLE)))
                        .status())
                .isEqualTo(409);
        assertThat(service.transitionStatus(List.of(new InventoryStatusTransition("DRILL-001", InventoryStatus.RENTED)))
                        .status())
                .isEqualTo(409);
        assertThat(service.transitionStatus(List.of(new InventoryStatusTransition("DRILL-001", null)))
                        .status())
                .isEqualTo(409);

        assertThat(rented.getStatus()).isEqualTo(InventoryStatus.RENTED);
        verifyNoInteractions(historyRepository);
    }

    @Test
    void locksInSerialOrderAndReportsAllFailuresWithoutChangingValidItems() {
        InventoryItem valid = new InventoryItem("A", "Drill", "Original", InventoryStatus.AVAILABLE);
        InventoryItem invalid = new InventoryItem("Z", "Drill", "Original", InventoryStatus.RETIRED);
        when(repository.findForUpdateBySerialNumber("A")).thenReturn(Optional.of(valid));
        when(repository.findForUpdateBySerialNumber("Z")).thenReturn(Optional.of(invalid));
        when(repository.findForUpdateBySerialNumber("MISSING")).thenReturn(Optional.empty());
        InventoryService service = new InventoryService(
                repository, historyRepository, cancellationInboxRepository, ObservationRegistry.NOOP, metrics);

        InventoryStatusTransitionOutcome outcome = service.transitionStatus(List.of(
                new InventoryStatusTransition("Z", InventoryStatus.AVAILABLE),
                new InventoryStatusTransition("A", InventoryStatus.RENTED),
                new InventoryStatusTransition("MISSING", InventoryStatus.RESERVED)));
        assertThat(outcome.status()).isEqualTo(409);
        assertThat(outcome.failedItems())
                .extracting(InventoryStatusTransitionOutcome.Failure::index)
                .containsExactly(0, 2);
        assertThat(outcome.failedItems())
                .extracting(InventoryStatusTransitionOutcome.Failure::code)
                .containsExactly("INVALID_INVENTORY_STATUS_TRANSITION", "INVENTORY_ITEM_NOT_FOUND");
        InOrder order = org.mockito.Mockito.inOrder(repository);
        order.verify(repository).findForUpdateBySerialNumber("A");
        order.verify(repository).findForUpdateBySerialNumber("MISSING");
        order.verify(repository).findForUpdateBySerialNumber("Z");
        assertThat(valid.getStatus()).isEqualTo(InventoryStatus.AVAILABLE);
        assertThat(invalid.getStatus()).isEqualTo(InventoryStatus.RETIRED);
        verifyNoInteractions(historyRepository);
    }

    @Test
    void validatesTheWholeBatchBeforeWritingHistory() {
        InventoryItem first = new InventoryItem("A", "Drill", "Original", InventoryStatus.AVAILABLE);
        InventoryItem second = new InventoryItem("Z", "Mixer", "Original", InventoryStatus.RESERVED);
        when(repository.findForUpdateBySerialNumber("A")).thenReturn(Optional.of(first));
        when(repository.findForUpdateBySerialNumber("Z")).thenAnswer(invocation -> {
            assertThat(first.getStatus()).isEqualTo(InventoryStatus.AVAILABLE);
            verifyNoInteractions(historyRepository);
            return Optional.of(second);
        });
        new InventoryService(
                        repository, historyRepository, cancellationInboxRepository, ObservationRegistry.NOOP, metrics)
                .transitionStatus(List.of(
                        new InventoryStatusTransition("Z", InventoryStatus.RENTED),
                        new InventoryStatusTransition("A", InventoryStatus.RESERVED)));
        ArgumentCaptor<InventoryStatusHistory> captor = ArgumentCaptor.forClass(InventoryStatusHistory.class);
        verify(historyRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(InventoryStatusHistory::getStatusFrom)
                .containsExactly(InventoryStatus.RESERVED, InventoryStatus.AVAILABLE);
        assertThat(first.getStatus()).isEqualTo(InventoryStatus.RESERVED);
        assertThat(second.getStatus()).isEqualTo(InventoryStatus.RENTED);
    }

    @Test
    void observesAcceptedTransitionAndRestoresParentScope() {
        List<Observation.Context> completed = new ArrayList<>();
        ObservationRegistry observations = recordingObservations(completed);
        InventoryService service =
                new InventoryService(repository, historyRepository, cancellationInboxRepository, observations, metrics);
        InventoryItem item = new InventoryItem("DRILL-001", "Drill", "Original", InventoryStatus.AVAILABLE);
        List<Observation.Context> repositoryContexts = new ArrayList<>();
        when(repository.findForUpdateBySerialNumber("DRILL-001")).thenAnswer(invocation -> {
            repositoryContexts.add(observations.getCurrentObservation().getContext());
            return Optional.of(item);
        });
        Observation parent = Observation.start("caller", observations);
        try (Observation.Scope _ = parent.openScope()) {
            assertThat(service.transitionStatus(
                                    List.of(new InventoryStatusTransition("DRILL-001", InventoryStatus.RESERVED)))
                            .status())
                    .isEqualTo(204);
            assertThat(observations.getCurrentObservation()).isSameAs(parent);
        } finally {
            parent.stop();
        }
        assertThat(observations.getCurrentObservation()).isNull();
        assertThat(completed).hasSize(1);
        Observation.Context context = completed.getFirst();
        assertThat(context.getParentObservation()).isSameAs(parent);
        assertThat(repositoryContexts).containsExactly(context);
        assertThat(context.getError()).isNull();
        assertThat(context.getLowCardinalityKeyValues()).containsExactly(KeyValue.of("outcome", "accepted"));
        assertThat(context.getHighCardinalityKeyValues()).containsExactly(KeyValue.of("batch.size", "1"));
        assertThat(metrics.get("inventory.status.transition")
                        .tag("outcome", "accepted")
                        .tag("error", "none")
                        .timer()
                        .count())
                .isOne();
        assertThat(metrics.get("inventory.status.transition.active")
                        .longTaskTimer()
                        .activeTasks())
                .isZero();
        assertThat(metrics.get("inventory.status.transition.batch.size")
                        .summary()
                        .count())
                .isOne();
        assertThat(metrics.get("inventory.status.transition.batch.size")
                        .summary()
                        .totalAmount())
                .isEqualTo(1);
    }

    @Test
    void observesRejectedBatchWithoutTechnicalErrorAndCountsSubmittedItems() {
        List<Observation.Context> completed = new ArrayList<>();
        ObservationRegistry observations = recordingObservations(completed);
        InventoryService service =
                new InventoryService(repository, historyRepository, cancellationInboxRepository, observations, metrics);
        InventoryItem item = new InventoryItem("DRILL-001", "Drill", "Original", InventoryStatus.AVAILABLE);
        when(repository.findForUpdateBySerialNumber("DRILL-001")).thenReturn(Optional.of(item));
        when(repository.findForUpdateBySerialNumber("MISSING")).thenReturn(Optional.empty());

        assertThat(service.transitionStatus(List.of(
                                new InventoryStatusTransition("DRILL-001", InventoryStatus.RESERVED),
                                new InventoryStatusTransition("MISSING", InventoryStatus.RESERVED)))
                        .status())
                .isEqualTo(404);

        assertThat(observations.getCurrentObservation()).isNull();
        assertThat(completed).hasSize(1);
        Observation.Context context = completed.getFirst();
        assertThat(context.getError()).isNull();
        assertThat(context.getLowCardinalityKeyValues()).containsExactly(KeyValue.of("outcome", "rejected"));
        assertThat(context.getHighCardinalityKeyValues())
                .containsExactlyInAnyOrder(KeyValue.of("batch.size", "2"), KeyValue.of("rejected.items", "1"));
        assertThat(metrics.get("inventory.status.transition")
                        .tag("outcome", "rejected")
                        .tag("error", "none")
                        .timer()
                        .count())
                .isOne();
        assertThat(metrics.get("inventory.status.transition.active")
                        .longTaskTimer()
                        .activeTasks())
                .isZero();
        assertThat(metrics.get("inventory.status.transition.batch.size")
                        .summary()
                        .count())
                .isOne();
        assertThat(metrics.get("inventory.status.transition.batch.size")
                        .summary()
                        .totalAmount())
                .isEqualTo(2);
        assertThat(item.getStatus()).isEqualTo(InventoryStatus.AVAILABLE);
        verifyNoInteractions(historyRepository);
        assertThat(metrics.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().getTags())
                        .allSatisfy(tag -> assertThat(tag.getKey()).isIn("outcome", "error")));
    }

    @ParameterizedTest
    @MethodSource("technicalFailures")
    void recordsAndRethrowsTechnicalFailureAndRestoresParentScope(Throwable failure) {
        List<Observation.Context> completed = new ArrayList<>();
        ObservationRegistry observations = recordingObservations(completed);
        InventoryService service =
                new InventoryService(repository, historyRepository, cancellationInboxRepository, observations, metrics);
        List<Observation.Context> repositoryContexts = new ArrayList<>();
        when(repository.findForUpdateBySerialNumber("DRILL-001")).thenAnswer(invocation -> {
            repositoryContexts.add(observations.getCurrentObservation().getContext());
            throw failure;
        });
        Observation parent = Observation.start("caller", observations);
        try (Observation.Scope _ = parent.openScope()) {
            assertThatThrownBy(() -> service.transitionStatus(
                            List.of(new InventoryStatusTransition("DRILL-001", InventoryStatus.RESERVED))))
                    .isSameAs(failure);
            assertThat(observations.getCurrentObservation()).isSameAs(parent);
        } finally {
            parent.stop();
        }
        assertThat(observations.getCurrentObservation()).isNull();
        assertThat(completed).hasSize(1);
        Observation.Context context = completed.getFirst();
        assertThat(repositoryContexts).containsExactly(context);
        assertThat(context.getError()).isSameAs(failure);
        assertThat(context.getLowCardinalityKeyValues()).containsExactly(KeyValue.of("outcome", "error"));
        assertThat(context.getHighCardinalityKeyValues()).containsExactly(KeyValue.of("batch.size", "1"));
        assertThat(metrics.get("inventory.status.transition")
                        .tag("outcome", "error")
                        .tag("error", failure.getClass().getSimpleName())
                        .timer()
                        .count())
                .isOne();
        assertThat(metrics.get("inventory.status.transition.active")
                        .longTaskTimer()
                        .activeTasks())
                .isZero();
        assertThat(metrics.get("inventory.status.transition.batch.size")
                        .summary()
                        .count())
                .isOne();
        assertThat(metrics.get("inventory.status.transition.batch.size")
                        .summary()
                        .totalAmount())
                .isEqualTo(1);
        verifyNoInteractions(historyRepository);
    }

    private static Stream<Throwable> technicalFailures() {
        return Stream.of(new IllegalStateException("Database unavailable"), new AssertionError("Forced failure"));
    }

    private ObservationRegistry recordingObservations(List<Observation.Context> completed) {
        ObservationRegistry observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(metrics));
        observations.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return context.getName().equals("inventory.status.transition");
            }

            @Override
            public void onStop(Observation.Context context) {
                completed.add(context);
            }
        });
        return observations;
    }

    @Test
    void delegatesHistoryFilteringPagingAndEveryDeterministicSort() {
        when(historyRepository.findAll(eq("DRILL"), any(Pageable.class))).thenReturn(Page.empty());
        InventoryService service = new InventoryService(
                repository, historyRepository, cancellationInboxRepository, ObservationRegistry.NOOP, metrics);

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
