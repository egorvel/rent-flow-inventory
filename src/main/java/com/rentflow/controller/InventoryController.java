package com.rentflow.controller;

import com.rentflow.converter.InventoryConverter;
import com.rentflow.dto.InventoryItemRequest;
import com.rentflow.dto.InventoryItemResponse;
import com.rentflow.dto.InventoryPageResponse;
import com.rentflow.model.InventoryStatus;
import com.rentflow.service.InventoryService;
import com.rentflow.service.InventorySortField;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.Set;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping(path = InventoryController.PATH, produces = MediaType.APPLICATION_JSON_VALUE)
public class InventoryController {

    public static final String PATH = "/api/v1/inventory";
    private static final Set<String> COLLECTION_PARAMETERS =
            Set.of("page", "size", "status", "type", "sort", "direction");

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

    @PutMapping(path = "/{serialNumber}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public InventoryItemResponse replace(
            @PathVariable
                    @Pattern(
                            regexp = InventoryItemRequest.SERIAL_NUMBER_PATTERN,
                            message = "must be a valid serial number")
                    String serialNumber,
            @Valid @RequestBody InventoryItemRequest request) {
        if (!serialNumber.equals(request.serialNumber())) {
            throw new RequestValidationException("serialNumber", "must match the path serial number");
        }

        return converter.toResponse(service.replace(serialNumber, request.type(), request.name(), request.status()));
    }

    @DeleteMapping("/{serialNumber}")
    public ResponseEntity<Void> delete(
            @PathVariable
                    @Pattern(
                            regexp = InventoryItemRequest.SERIAL_NUMBER_PATTERN,
                            message = "must be a valid serial number")
                    String serialNumber) {
        service.delete(serialNumber);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public InventoryPageResponse list(
            HttpServletRequest servletRequest,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "must be at least 0") int page,
            @RequestParam(defaultValue = "20")
                    @Min(value = 1, message = "must be at least 1") @Max(value = 100, message = "must be at most 100") int size,
            @RequestParam(required = false) InventoryStatus status,
            @RequestParam(required = false)
                    @Pattern(regexp = "(?U).*\\S.*", message = "must not be blank")
                    @Size(max = 100, message = "must contain at most 100 characters") String type,
            @RequestParam(defaultValue = "serialNumber") String sort,
            @RequestParam(defaultValue = "asc") String direction) {
        validateCollectionParameters(servletRequest);

        var sortField = parseSortField(sort);
        var sortDirection = parseDirection(direction);
        var normalizedType = type == null ? null : type.strip();
        return converter.toPageResponse(service.list(page, size, status, normalizedType, sortField, sortDirection));
    }

    private void validateCollectionParameters(HttpServletRequest request) {
        request.getParameterMap().forEach((name, values) -> {
            if (!COLLECTION_PARAMETERS.contains(name)) {
                throw new RequestValidationException(name, "is not supported");
            }
            if (values.length != 1) {
                throw new RequestValidationException(name, "must be supplied exactly once");
            }
            if (values[0] == null || values[0].isBlank()) {
                throw new RequestValidationException(name, "must not be blank");
            }
        });
    }

    private InventorySortField parseSortField(String sort) {
        try {
            return InventorySortField.fromApiName(sort);
        } catch (IllegalArgumentException exception) {
            throw new RequestValidationException("sort", "must be one of serialNumber, type, name, or status");
        }
    }

    private Sort.Direction parseDirection(String direction) {
        try {
            return Sort.Direction.fromString(direction);
        } catch (IllegalArgumentException exception) {
            throw new RequestValidationException("direction", "must be asc or desc");
        }
    }
}
