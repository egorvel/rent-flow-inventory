package com.rentflow.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rentflow.model.InventoryStatusHistory;

public interface InventoryStatusHistoryRepository extends JpaRepository<InventoryStatusHistory, Long> {}
