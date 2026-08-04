package com.rentflow.controller;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import com.rentflow.dto.ProblemResponse;
import com.rentflow.model.InventoryStatus;
import com.rentflow.service.InvalidInventoryStatusTransitionException;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void sanitizesUnexpectedFailuresWithoutStartingASpringContext() {
        String sensitiveText = "IllegalStateException stack trace SELECT password jdbc:postgresql://db/rentflow "
                + "inventory-secret environment-value request-body-value";
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/inventory");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer inventory-secret");
        request.setContent("request-body-value".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<ProblemResponse> response =
                handler.handleUnexpected(new IllegalStateException(sensitiveText), new ServletWebRequest(request));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody())
                .isEqualTo(new ProblemResponse(
                        "urn:rentflow:problem:internal-error",
                        "Internal server error",
                        500,
                        "An unexpected error occurred.",
                        "/api/v1/inventory",
                        "INTERNAL_ERROR",
                        java.util.List.of()));
        assertThat(response.getBody().toString())
                .doesNotContain(
                        "IllegalStateException",
                        "stack trace",
                        "SELECT",
                        "jdbc:postgresql",
                        "password",
                        "inventory-secret",
                        "environment-value",
                        "request-body-value");
    }

    @Test
    void convertsOtherMvcClientErrorsWithoutLeakingFrameworkDetails() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/inventory");

        ResponseEntity<Object> response = handler.handleExceptionInternal(
                new IllegalArgumentException("framework detail"),
                null,
                HttpHeaders.EMPTY,
                HttpStatus.BAD_REQUEST,
                new ServletWebRequest(request));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody())
                .isEqualTo(new ProblemResponse(
                        "urn:rentflow:problem:http-error",
                        "Request failed",
                        400,
                        "The request could not be processed.",
                        "/api/v1/inventory",
                        "HTTP_ERROR",
                        java.util.List.of()));
    }

    @Test
    void returnsTheStableInvalidTransitionProblem() {
        MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/api/v1/inventory/DRILL-001/status");
        InvalidInventoryStatusTransitionException exception = new InvalidInventoryStatusTransitionException(
                "DRILL-001", InventoryStatus.RENTED, InventoryStatus.AVAILABLE);

        ResponseEntity<ProblemResponse> response =
                handler.handleInvalidTransition(exception, new ServletWebRequest(request));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody())
                .isEqualTo(new ProblemResponse(
                        "urn:rentflow:problem:invalid-inventory-status-transition",
                        "Invalid inventory status transition",
                        409,
                        "Inventory item 'DRILL-001' cannot transition from RENTED to AVAILABLE.",
                        "/api/v1/inventory/DRILL-001/status",
                        "INVALID_INVENTORY_STATUS_TRANSITION",
                        java.util.List.of()));
    }

    @Test
    void logsUnexpectedFailuresWithTheCompleteStackTrace(CapturedOutput output) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/inventory/DRILL-001");

        handler.handleUnexpected(new IllegalStateException("boom"), new ServletWebRequest(request));

        assertThat(output)
                .contains("Unexpected failure handling GET /api/v1/inventory/DRILL-001")
                .contains("java.lang.IllegalStateException: boom")
                .contains(
                        "at com.rentflow.controller.ApiExceptionHandlerTest.logsUnexpectedFailuresWithTheCompleteStackTrace");
    }
}
