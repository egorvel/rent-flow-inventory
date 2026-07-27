package com.rentflow.service;

import com.rentflow.model.InventoryItem;
import com.rentflow.model.InventoryStatus;
import com.rentflow.repository.InventoryRepository;
import org.springframework.dao.DataIntegrityViolationException;
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
}
