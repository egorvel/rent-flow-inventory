package com.rentflow.controller;

import com.rentflow.converter.InventoryConverter;
import com.rentflow.dto.InventoryItemRequest;
import com.rentflow.dto.InventoryItemResponse;
import com.rentflow.service.InventoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping(path = InventoryController.PATH, produces = MediaType.APPLICATION_JSON_VALUE)
public class InventoryController {

    public static final String PATH = "/api/v1/inventory";

    private final InventoryService service;
    private final InventoryConverter converter;

    public InventoryController(InventoryService service, InventoryConverter converter) {
        this.service = service;
        this.converter = converter;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<InventoryItemResponse> create(@Valid @RequestBody InventoryItemRequest request) {
        var item = service.create(request.serialNumber(), request.type(), request.name(), request.status());
        var location = URI.create(PATH + "/" + item.getSerialNumber());
        return ResponseEntity.created(location).body(converter.toResponse(item));
    }

    @GetMapping("/{serialNumber}")
    public InventoryItemResponse get(
            @PathVariable
                    @Pattern(
                            regexp = InventoryItemRequest.SERIAL_NUMBER_PATTERN,
                            message = "must be a valid serial number")
                    String serialNumber) {
        return converter.toResponse(service.get(serialNumber));
    }
}
