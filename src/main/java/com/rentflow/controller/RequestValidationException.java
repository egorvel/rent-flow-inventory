package com.rentflow.controller;

import java.util.List;

import com.rentflow.dto.ViolationResponse;

class RequestValidationException extends RuntimeException {

    private final List<ViolationResponse> violations;

    RequestValidationException(String field, String message) {
        this(List.of(new ViolationResponse(field, message)));
    }

    RequestValidationException(List<ViolationResponse> violations) {
        super("Invalid request value");
        this.violations = List.copyOf(violations);
    }

    List<ViolationResponse> getViolations() {
        return violations;
    }
}
