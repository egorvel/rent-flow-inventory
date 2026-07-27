package com.rentflow.controller;

import com.rentflow.dto.ProblemResponse;
import com.rentflow.dto.ViolationResponse;
import com.rentflow.model.InventoryStatus;
import com.rentflow.service.InventoryItemAlreadyExistsException;
import com.rentflow.service.InventoryItemNotFoundException;
import jakarta.validation.ConstraintViolationException;
import java.util.Comparator;
import java.util.List;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.databind.exc.InvalidFormatException;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Comparator<ViolationResponse> VIOLATION_ORDER =
            Comparator.comparing(ViolationResponse::field).thenComparing(ViolationResponse::message);

    @ExceptionHandler(InventoryItemNotFoundException.class)
    ResponseEntity<ProblemResponse> handleNotFound(InventoryItemNotFoundException exception, WebRequest request) {
        return response(
                HttpStatus.NOT_FOUND,
                "urn:rentflow:problem:inventory-item-not-found",
                "Inventory item not found",
                "Inventory item '" + exception.getSerialNumber() + "' was not found.",
                "INVENTORY_ITEM_NOT_FOUND",
                request,
                List.of());
    }

    @ExceptionHandler(InventoryItemAlreadyExistsException.class)
    ResponseEntity<ProblemResponse> handleConflict(InventoryItemAlreadyExistsException exception, WebRequest request) {
        return response(
                HttpStatus.CONFLICT,
                "urn:rentflow:problem:inventory-item-already-exists",
                "Inventory item already exists",
                "Inventory item '" + exception.getSerialNumber() + "' already exists.",
                "INVENTORY_ITEM_ALREADY_EXISTS",
                request,
                List.of());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemResponse> handleConstraintViolation(
            ConstraintViolationException exception, WebRequest request) {
        var violations = exception.getConstraintViolations().stream()
                .map(violation -> new ViolationResponse(
                        finalPathSegment(violation.getPropertyPath().toString()), violation.getMessage()))
                .sorted(VIOLATION_ORDER)
                .toList();
        return validationResponse(violations, request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var violations = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ViolationResponse(error.getField(), error.getDefaultMessage()))
                .sorted(VIOLATION_ORDER)
                .toList();
        return objectResponse(validationProblem(violations, request), HttpStatus.BAD_REQUEST);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        var violations = exception.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new ViolationResponse(parameterName(result), message(error))))
                .sorted(VIOLATION_ORDER)
                .toList();
        return objectResponse(validationProblem(violations, request), HttpStatus.BAD_REQUEST);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var invalidFormat = findCause(exception, InvalidFormatException.class);
        if (invalidFormat != null && invalidFormat.getTargetType() == InventoryStatus.class) {
            var violations = List.of(new ViolationResponse("status", "must be a defined inventory status"));
            return objectResponse(validationProblem(violations, request), HttpStatus.BAD_REQUEST);
        }

        var problem = problem(
                HttpStatus.BAD_REQUEST,
                "urn:rentflow:problem:malformed-json",
                "Malformed JSON",
                "The request body could not be read.",
                "MALFORMED_JSON",
                request,
                List.of());
        return objectResponse(problem, HttpStatus.BAD_REQUEST);
    }

    private ResponseEntity<ProblemResponse> validationResponse(List<ViolationResponse> violations, WebRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(validationProblem(violations, request));
    }

    private ProblemResponse validationProblem(List<ViolationResponse> violations, WebRequest request) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "urn:rentflow:problem:validation-failed",
                "Request validation failed",
                "One or more request values are invalid.",
                "VALIDATION_FAILED",
                request,
                violations);
    }

    private ResponseEntity<ProblemResponse> response(
            HttpStatus status,
            String type,
            String title,
            String detail,
            String code,
            WebRequest request,
            List<ViolationResponse> violations) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem(status, type, title, detail, code, request, violations));
    }

    private ResponseEntity<Object> objectResponse(ProblemResponse problem, HttpStatus status) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private ProblemResponse problem(
            HttpStatus status,
            String type,
            String title,
            String detail,
            String code,
            WebRequest request,
            List<ViolationResponse> violations) {
        return new ProblemResponse(type, title, status.value(), detail, requestPath(request), code, violations);
    }

    private String requestPath(WebRequest request) {
        return ((ServletWebRequest) request).getRequest().getRequestURI();
    }

    private String parameterName(ParameterValidationResult result) {
        var name = result.getMethodParameter().getParameterName();
        return name == null ? "request" : name;
    }

    private String message(MessageSourceResolvable error) {
        var message = error.getDefaultMessage();
        return message == null ? "is invalid" : message;
    }

    private String finalPathSegment(String path) {
        var separator = path.lastIndexOf('.');
        return separator < 0 ? path : path.substring(separator + 1);
    }

    private <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
        var current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }
}
