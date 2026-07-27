package com.rentflow.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

public record ProblemResponse(
        String type,
        String title,
        int status,
        String detail,
        String instance,
        String code,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ViolationResponse> violations) {}
