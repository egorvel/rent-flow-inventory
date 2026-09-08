package com.rentflow.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rentflow.model.InventoryItem;
import com.rentflow.model.InventoryStatus;
import com.rentflow.model.InventoryStatusHistory;
import com.rentflow.model.InventoryStatusTransition;
import com.rentflow.model.InventoryStatusTransitionOutcome;
import com.rentflow.repository.InventoryRepository;
import com.rentflow.repository.InventoryStatusHistoryRepository;

@Service
public class InventoryService {

    private final InventoryRepository repository;
    private final InventoryStatusHistoryRepository historyRepository;

    public InventoryService(InventoryRepository repository, InventoryStatusHistoryRepository historyRepository) {
        this.repository = repository;
        this.historyRepository = historyRepository;
    }

    @Transactional
    public InventoryItem create(InventoryItem item) {
        if (repository.existsById(item.getSerialNumber())) {
            throw new InventoryItemAlreadyExistsException(item.getSerialNumber());
        }

        try {
            return repository.saveAndFlush(item);
        } catch (DataIntegrityViolationException exception) {
            throw new InventoryItemAlreadyExistsException(item.getSerialNumber());
        }
    }

    @Transactional(readOnly = true)
    public InventoryItem get(String serialNumber) {
        return repository.findById(serialNumber).orElseThrow(() -> new InventoryItemNotFoundException(serialNumber));
    }

    @Transactional
    public InventoryItem replace(InventoryItem item) {
        InventoryItem resultItem = repository
                .findById(item.getSerialNumber())
                .orElseThrow(() -> new InventoryItemNotFoundException(item.getSerialNumber()));
        resultItem.replaceDetails(item);
        return resultItem;
    }

    @Transactional
    public void delete(String serialNumber) {
        InventoryItem item =
                repository.findById(serialNumber).orElseThrow(() -> new InventoryItemNotFoundException(serialNumber));
        repository.delete(item);
    }

    @Transactional
    public InventoryStatusTransitionOutcome transitionStatus(List<InventoryStatusTransition> transitions) {
        Map<String, InventoryItem> items = new HashMap<>();
        List<String> serialNumbers = transitions.stream()
                .map(InventoryStatusTransition::serialNumber)
                .distinct()
                .sorted()
                .toList();
        for (String serialNumber : serialNumbers) {
            repository.findForUpdateBySerialNumber(serialNumber).ifPresent(item -> items.put(serialNumber, item));
        }

        List<InventoryStatusTransitionOutcome.Failure> failures = new ArrayList<>();
        for (int index = 0; index < transitions.size(); index++) {
            InventoryStatusTransition transition = transitions.get(index);
            String serialNumber = transition.serialNumber();
            InventoryItem item = items.get(serialNumber);
            if (item == null) {
                failures.add(new InventoryStatusTransitionOutcome.Failure(
                        index,
                        serialNumber,
                        transition.status(),
                        "INVENTORY_ITEM_NOT_FOUND",
                        "Inventory item '" + serialNumber + "' was not found."));
            } else if (!item.getStatus().canTransitionTo(transition.status())) {
                failures.add(new InventoryStatusTransitionOutcome.Failure(
                        index,
                        serialNumber,
                        transition.status(),
                        "INVALID_INVENTORY_STATUS_TRANSITION",
                        "Inventory item '" + serialNumber + "' cannot transition from " + item.getStatus() + " to "
                                + transition.status() + "."));
            }
        }
        if (!failures.isEmpty()) {
            return InventoryStatusTransitionOutcome.rejected(failures);
        }

        for (InventoryStatusTransition transition : transitions) {
            InventoryItem item = items.get(transition.serialNumber());
            InventoryStatus statusFrom = item.getStatus();
            item.setStatus(transition.status());
            historyRepository.save(
                    new InventoryStatusHistory(transition.serialNumber(), statusFrom, transition.status()));
        }
        return InventoryStatusTransitionOutcome.success();
    }

    @Transactional(readOnly = true)
    public Page<InventoryItem> list(
            int page,
            int size,
            InventoryStatus status,
            String type,
            InventorySortField sortField,
            Sort.Direction direction) {
        ArrayList<Sort.Order> orders = new java.util.ArrayList<>();
        orders.add(new Sort.Order(direction, sortField.property()));
        if (sortField != InventorySortField.SERIAL_NUMBER) {
            orders.add(Sort.Order.asc("serialNumber"));
        }
        return repository.findAll(status, type, PageRequest.of(page, size, Sort.by(orders)));
    }

    @Transactional(readOnly = true)
    public Page<InventoryStatusHistory> listStatusHistory(
            int page,
            int size,
            String serialNumber,
            InventoryStatusHistorySortField sortField,
            Sort.Direction direction) {
        ArrayList<Sort.Order> orders = new ArrayList<>();
        orders.add(new Sort.Order(direction, sortField.property()));
        orders.add(new Sort.Order(direction, "id"));
        return historyRepository.findAll(serialNumber, PageRequest.of(page, size, Sort.by(orders)));
    }
}
