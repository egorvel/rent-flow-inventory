package com.rentflow.service;

import java.util.ArrayList;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rentflow.model.InventoryItem;
import com.rentflow.model.InventoryStatus;
import com.rentflow.repository.InventoryRepository;

@Service
public class InventoryService {

    private final InventoryRepository repository;

    public InventoryService(InventoryRepository repository) {
        this.repository = repository;
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
}
