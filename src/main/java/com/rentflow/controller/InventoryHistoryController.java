package com.rentflow.controller;

import java.util.Set;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.rentflow.converter.InventoryStatusHistoryConverter;
import com.rentflow.dto.InventoryStatusHistoryDTO;
import com.rentflow.dto.ProblemResponse;
import com.rentflow.service.InventoryService;
import com.rentflow.service.InventoryStatusHistorySortField;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Validated
@Tag(name = "Inventory")
@RequestMapping(path = InventoryHistoryController.PATH, produces = MediaType.APPLICATION_JSON_VALUE)
public class InventoryHistoryController {

    public static final String PATH = "/api/v1/inventory-history";
    private static final Set<String> COLLECTION_PARAMETERS =
            Set.of("page", "size", "serialNumber", "sort", "direction");

    private final InventoryService service;
    private final InventoryStatusHistoryConverter converter;

    public InventoryHistoryController(InventoryService service, InventoryStatusHistoryConverter converter) {
        this.service = service;
        this.converter = converter;
    }

    @Operation(operationId = "listInventoryStatusHistory", summary = "List inventory status history")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Bounded inventory status history page returned."),
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
    public PagedModel<InventoryStatusHistoryDTO> list(
            @Parameter(hidden = true) HttpServletRequest servletRequest,
            @Parameter(description = "Zero-based page number.", schema = @Schema(defaultValue = "0", minimum = "0"))
                    @RequestParam(defaultValue = "0")
                    @Min(value = 0, message = "must be at least 0") int page,
            @Parameter(
                            description = "Maximum history records returned per page.",
                            schema = @Schema(defaultValue = "20", minimum = "1", maximum = "100"))
                    @RequestParam(defaultValue = "20")
                    @Min(value = 1, message = "must be at least 1") @Max(value = 100, message = "must be at most 100") int size,
            @Parameter(
                            description = "Case-sensitive literal serial-number substring.",
                            schema = @Schema(minLength = 1, maxLength = 64, pattern = "(?U).*\\S.*"))
                    @RequestParam(required = false)
                    @Pattern(regexp = "(?U).*\\S.*", message = "must not be blank")
                    @Size(min = 1, max = 64, message = "must contain between 1 and 64 characters") String serialNumber,
            @Parameter(
                            description = "Primary sort field.",
                            schema =
                                    @Schema(
                                            defaultValue = "timestamp",
                                            allowableValues = {"serialNumber", "statusFrom", "statusTo", "timestamp"}))
                    @RequestParam(defaultValue = "timestamp")
                    String sort,
            @Parameter(
                            description = "Primary sort direction, parsed case-insensitively.",
                            schema =
                                    @Schema(
                                            defaultValue = "desc",
                                            allowableValues = {"asc", "desc"}))
                    @RequestParam(defaultValue = "desc")
                    String direction) {
        validateCollectionParameters(servletRequest);
        InventoryStatusHistorySortField sortField = parseSortField(sort);
        Sort.Direction sortDirection = parseDirection(direction);
        Page<InventoryStatusHistoryDTO> result = service.listStatusHistory(
                        page, size, serialNumber, sortField, sortDirection)
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

    private InventoryStatusHistorySortField parseSortField(String sort) {
        try {
            return InventoryStatusHistorySortField.fromApiName(sort);
        } catch (IllegalArgumentException exception) {
            throw new RequestValidationException(
                    "sort", "must be one of serialNumber, statusFrom, statusTo, or timestamp");
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
