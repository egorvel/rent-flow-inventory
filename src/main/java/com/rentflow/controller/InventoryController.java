package com.rentflow.controller;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.rentflow.converter.InventoryConverter;
import com.rentflow.dto.InventoryItemDTO;
import com.rentflow.dto.InventoryStatusTransitionProblemResponse;
import com.rentflow.dto.InventoryStatusUpdateDTO;
import com.rentflow.dto.ProblemResponse;
import com.rentflow.dto.ViolationResponse;
import com.rentflow.model.InventoryItem;
import com.rentflow.model.InventoryStatus;
import com.rentflow.model.InventoryStatusTransition;
import com.rentflow.service.IdempotentStatusTransitionService;
import com.rentflow.service.InventoryService;
import com.rentflow.service.InventorySortField;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Validated
@Tag(name = "Inventory")
@RequestMapping(path = InventoryController.PATH, produces = MediaType.APPLICATION_JSON_VALUE)
public class InventoryController {

    public static final String PATH = "/api/v1/inventory";
    private static final Set<String> COLLECTION_PARAMETERS =
            Set.of("page", "size", "status", "type", "sort", "direction");

    private final InventoryService service;
    private final InventoryConverter converter;
    private final IdempotentStatusTransitionService transitions;

    public InventoryController(
            InventoryService service, InventoryConverter converter, IdempotentStatusTransitionService transitions) {
        this.transitions = transitions;
        this.service = service;
        this.converter = converter;
    }

    @Operation(operationId = "createInventoryItem", summary = "Create an inventory item")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Inventory item created.",
                headers =
                        @Header(
                                name = HttpHeaders.LOCATION,
                                description = "Canonical path of the created inventory item.",
                                schema = @Schema(type = "string", format = "uri")),
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = InventoryItemDTO.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Request validation failed or the JSON body is malformed.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(
                responseCode = "406",
                description = "No acceptable response representation is available.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(
                responseCode = "409",
                description = "The serial number already exists.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(
                responseCode = "415",
                description = "The request media type is unsupported.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(
                responseCode = "500",
                description = "An unexpected server error occurred.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class)))
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<InventoryItemDTO> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Complete inventory item representation.",
                            required = true)
                    @Valid @RequestBody
                    InventoryItemDTO request) {
        InventoryItem item = service.create(converter.toModel(request));
        URI location = URI.create(PATH + "/" + item.getSerialNumber());
        return ResponseEntity.created(location).body(converter.toResponse(item));
    }

    @Operation(operationId = "getInventoryItem", summary = "Retrieve an inventory item")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Inventory item found.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = InventoryItemDTO.class))),
        @ApiResponse(
                responseCode = "400",
                description = "The serial-number path value is invalid.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "The inventory item does not exist.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(
                responseCode = "406",
                description = "No acceptable response representation is available.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(
                responseCode = "500",
                description = "An unexpected server error occurred.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class)))
    })
    @GetMapping("/{serialNumber}")
    public InventoryItemDTO get(
            @Parameter(
                            description = "Case-sensitive inventory serial number.",
                            required = true,
                            schema =
                                    @Schema(
                                            minLength = 1,
                                            maxLength = 64,
                                            pattern = InventoryItemDTO.SERIAL_NUMBER_PATTERN))
                    @PathVariable
                    @Pattern(regexp = InventoryItemDTO.SERIAL_NUMBER_PATTERN, message = "must be a valid serial number") String serialNumber) {
        return converter.toResponse(service.get(serialNumber));
    }

    @Operation(operationId = "replaceInventoryItem", summary = "Fully replace an inventory item")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Inventory item replaced.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = InventoryItemDTO.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Request validation failed, serial numbers differ, or the JSON body is malformed.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "The inventory item does not exist.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(
                responseCode = "406",
                description = "No acceptable response representation is available.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(
                responseCode = "415",
                description = "The request media type is unsupported.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(
                responseCode = "500",
                description = "An unexpected server error occurred.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class)))
    })
    @PutMapping(path = "/{serialNumber}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public InventoryItemDTO replace(
            @Parameter(
                            description = "Case-sensitive inventory serial number.",
                            required = true,
                            schema =
                                    @Schema(
                                            minLength = 1,
                                            maxLength = 64,
                                            pattern = InventoryItemDTO.SERIAL_NUMBER_PATTERN))
                    @PathVariable
                    @Pattern(regexp = InventoryItemDTO.SERIAL_NUMBER_PATTERN, message = "must be a valid serial number") String serialNumber,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Complete replacement representation with a matching serial number.",
                            required = true)
                    @Valid @RequestBody
                    InventoryItemDTO request) {
        if (!serialNumber.equals(request.serialNumber())) {
            throw new RequestValidationException("serialNumber", "must match the path serial number");
        }
        InventoryItem item = converter.toModel(request);
        return converter.toResponse(service.replace(item));
    }

    @Operation(
            operationId = "transitionInventoryStatus",
            summary = "Transition inventory statuses atomically",
            description =
                    "Required UUID v4 key. Completed outcomes replay for seven days without extending expiry. A new business attempt requires a new key.",
            parameters =
                    @Parameter(
                            name = "Idempotency-Key",
                            in = ParameterIn.HEADER,
                            required = true,
                            description =
                                    "Exactly one canonical UUID v4; case insensitive. Global scope for this endpoint.",
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "uuid",
                                            minLength = 36,
                                            maxLength = 36,
                                            pattern =
                                                    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")))
    @ApiResponses({
        @ApiResponse(
                responseCode = "204",
                headers = {
                    @Header(
                            name = "Idempotency-Replayed",
                            description = "Whether this is a saved outcome.",
                            schema = @Schema(type = "boolean")),
                    @Header(
                            name = "Idempotency-Key-Expires-At",
                            description =
                                    "Seven days after database-recorded completion; replay does not extend expiry.",
                            schema = @Schema(type = "string", format = "date-time"))
                },
                description = "All inventory statuses transitioned.",
                content = @Content),
        @ApiResponse(
                responseCode = "400",
                description = "The request body, batch size, or serial uniqueness is invalid.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(
                responseCode = "404",
                headers = {
                    @Header(
                            name = "Idempotency-Replayed",
                            description = "Whether this is a saved outcome.",
                            schema = @Schema(type = "boolean")),
                    @Header(
                            name = "Idempotency-Key-Expires-At",
                            description =
                                    "Seven days after database-recorded completion; replay does not extend expiry.",
                            schema = @Schema(type = "string", format = "date-time"))
                },
                description = "Every failed entry is a missing item; no changes were committed.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = InventoryStatusTransitionProblemResponse.class))),
        @ApiResponse(
                responseCode = "406",
                description = "No acceptable response representation is available.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(
                responseCode = "409",
                headers = {
                    @Header(
                            name = "Retry-After",
                            description = "1 second for IDEMPOTENCY_IN_PROGRESS only.",
                            schema = @Schema(type = "integer")),
                    @Header(
                            name = "Idempotency-Replayed",
                            description = "Present only for terminal lifecycle outcomes.",
                            schema = @Schema(type = "boolean")),
                    @Header(
                            name = "Idempotency-Key-Expires-At",
                            description = "Present only for terminal lifecycle outcomes.",
                            schema = @Schema(type = "string", format = "date-time"))
                },
                description =
                        "A lifecycle conflict with all failed entries, or IDEMPOTENCY_IN_PROGRESS while the key executes (retry after 1 second).",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema =
                                        @Schema(
                                                anyOf = {
                                                    InventoryStatusTransitionProblemResponse.class,
                                                    ProblemResponse.class
                                                }))),
        @ApiResponse(
                responseCode = "415",
                description = "The request media type is unsupported.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(
                responseCode = "422",
                description = "IDEMPOTENCY_KEY_REUSED: an unexpired completed key has a different validated payload.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(
                responseCode = "500",
                description = "An unexpected server error occurred.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class)))
    })
    @PatchMapping(path = "/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> transitionStatus(
            @Parameter(hidden = true) HttpServletRequest servletRequest,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "1–100 unique serial/status objects. All transitions and history commit atomically.",
                            required = true,
                            content =
                                    @Content(
                                            array =
                                                    @ArraySchema(
                                                            minItems = 1,
                                                            maxItems = 100,
                                                            schema =
                                                                    @Schema(
                                                                            implementation =
                                                                                    InventoryStatusUpdateDTO.class))))
                    @Valid @RequestBody
                    @Size(min = 1, max = 100, message = "must contain between 1 and 100 items") List<@NotNull(message = "must not be null") InventoryStatusUpdateDTO> request) {
        List<String> keys = Collections.list(servletRequest.getHeaders("Idempotency-Key"));
        if (keys.size() != 1
                || !keys.getFirst()
                        .matches(
                                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")) {
            throw new RequestValidationException(
                    "Idempotency-Key", "must be supplied exactly once as a canonical UUID v4");
        }
        UUID key = UUID.fromString(keys.getFirst());
        Set<String> serialNumbers = new HashSet<>();
        List<ViolationResponse> violations = new ArrayList<>();
        for (int index = 0; index < request.size(); index++) {
            if (!serialNumbers.add(request.get(index).serialNumber())) {
                violations.add(new ViolationResponse("[" + index + "].serialNumber", "must not be repeated"));
            }
        }
        if (!violations.isEmpty()) {
            throw new RequestValidationException(violations);
        }
        IdempotentStatusTransitionService.Result result = transitions.transition(
                key,
                request.stream()
                        .map(item -> new InventoryStatusTransition(item.serialNumber(), item.status()))
                        .toList());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(
                        result.outcome().status())
                .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .header("Idempotency-Key-Expires-At", result.expiresAt().toString());
        if (result.outcome().status() == 204) {
            return response.build();
        }
        return response.contentType(MediaType.APPLICATION_PROBLEM_JSON).body(converter.toResponse(result.outcome()));
    }

    @Operation(operationId = "deleteInventoryItem", summary = "Permanently delete an inventory item")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Inventory item permanently deleted."),
        @ApiResponse(
                responseCode = "400",
                description = "The serial-number path value is invalid.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "The inventory item does not exist.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(
                responseCode = "500",
                description = "An unexpected server error occurred.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class)))
    })
    @DeleteMapping("/{serialNumber}")
    public ResponseEntity<Void> delete(
            @Parameter(
                            description = "Case-sensitive inventory serial number.",
                            required = true,
                            schema =
                                    @Schema(
                                            minLength = 1,
                                            maxLength = 64,
                                            pattern = InventoryItemDTO.SERIAL_NUMBER_PATTERN))
                    @PathVariable
                    @Pattern(regexp = InventoryItemDTO.SERIAL_NUMBER_PATTERN, message = "must be a valid serial number") String serialNumber) {
        service.delete(serialNumber);
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "listInventoryItems", summary = "List, filter, and sort inventory items")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Bounded inventory page returned."),
        @ApiResponse(
                responseCode = "400",
                description = "One or more query parameters are invalid.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(
                responseCode = "406",
                description = "No acceptable response representation is available.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(
                responseCode = "500",
                description = "An unexpected server error occurred.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class)))
    })
    @GetMapping
    public PagedModel<InventoryItemDTO> list(
            @Parameter(hidden = true) HttpServletRequest servletRequest,
            @Parameter(description = "Zero-based page number.", schema = @Schema(defaultValue = "0", minimum = "0"))
                    @RequestParam(defaultValue = "0")
                    @Min(value = 0, message = "must be at least 0") int page,
            @Parameter(
                            description = "Maximum items returned per page.",
                            schema = @Schema(defaultValue = "20", minimum = "1", maximum = "100"))
                    @RequestParam(defaultValue = "20")
                    @Min(value = 1, message = "must be at least 1") @Max(value = 100, message = "must be at most 100") int size,
            @Parameter(
                            description = "Exact inventory status filter.",
                            schema =
                                    @Schema(
                                            allowableValues = {
                                                "AVAILABLE",
                                                "RESERVED",
                                                "RENTED",
                                                "INSPECTION_REQUIRED",
                                                "UNDER_MAINTENANCE",
                                                "RETIRED"
                                            }))
                    @RequestParam(required = false)
                    InventoryStatus status,
            @Parameter(
                            description = "Case-insensitive exact equipment-type filter.",
                            schema = @Schema(minLength = 1, maxLength = 100, pattern = "(?U).*\\S.*"))
                    @RequestParam(required = false)
                    @Pattern(regexp = "(?U).*\\S.*", message = "must not be blank")
                    @Size(min = 1, max = 100, message = "must contain between 1 and 100 characters") String type,
            @Parameter(
                            description = "Primary sort field.",
                            schema =
                                    @Schema(
                                            defaultValue = "serialNumber",
                                            allowableValues = {"serialNumber", "type", "name", "status"}))
                    @RequestParam(defaultValue = "serialNumber")
                    String sort,
            @Parameter(
                            description = "Primary sort direction, parsed case-insensitively.",
                            schema =
                                    @Schema(
                                            defaultValue = "asc",
                                            allowableValues = {"asc", "desc"}))
                    @RequestParam(defaultValue = "asc")
                    String direction) {
        validateCollectionParameters(servletRequest);

        InventorySortField sortField = parseSortField(sort);
        Sort.Direction sortDirection = parseDirection(direction);
        String normalizedType = type == null ? null : type.strip();
        Page<InventoryItemDTO> result = service.list(page, size, status, normalizedType, sortField, sortDirection)
                .map(converter::toResponse);
        return new PagedModel<>(result);
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
