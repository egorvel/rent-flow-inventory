package com.rentflow.service;

import com.rentflow.model.InventoryItem;
import com.rentflow.model.InventoryStatus;
import com.rentflow.repository.InventoryRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryService {

    private final InventoryRepository repository;

    public InventoryService(InventoryRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public InventoryItem create(String serialNumber, String type, String name, InventoryStatus status) {
        if (repository.existsById(serialNumber)) {
            throw new InventoryItemAlreadyExistsException(serialNumber);
        }

        try {
            return repository.saveAndFlush(new InventoryItem(serialNumber, type, name, status));
        } catch (DataIntegrityViolationException exception) {
            throw new InventoryItemAlreadyExistsException(serialNumber);
        }
    }

    @Transactional(readOnly = true)
    public InventoryItem get(String serialNumber) {
        return repository.findById(serialNumber).orElseThrow(() -> new InventoryItemNotFoundException(serialNumber));
    }

    @Transactional
    public InventoryItem replace(String serialNumber, String type, String name, InventoryStatus status) {
        var item =
                repository.findById(serialNumber).orElseThrow(() -> new InventoryItemNotFoundException(serialNumber));
        item.replaceDetails(type, name, status);
        return item;
    }

    @Transactional
    public void delete(String serialNumber) {
        var item =
                repository.findById(serialNumber).orElseThrow(() -> new InventoryItemNotFoundException(serialNumber));
        repository.delete(item);
    }

    @Transactional(readOnly = true)
    public Page<InventoryItem> list(
            int page,
            int size,
            InventoryStatus status,
            String type,
            InventorySortField sortField,
            Sort.Direction direction) {
        var orders = new java.util.ArrayList<Sort.Order>();
        orders.add(new Sort.Order(direction, sortField.entityAttribute()));
        if (sortField != InventorySortField.SERIAL_NUMBER) {
            orders.add(Sort.Order.asc("serialNumber"));
        }
        return repository.findAll(status, type, PageRequest.of(page, size, Sort.by(orders)));
    }
}
