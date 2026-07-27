package com.rentflow.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.rentflow.dto.ProblemResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void sanitizesUnexpectedFailuresWithoutStartingASpringContext() {
        var sensitiveText = "IllegalStateException stack trace SELECT password jdbc:postgresql://db/rentflow "
                + "inventory-secret environment-value request-body-value";
        var request = new MockHttpServletRequest("POST", "/api/v1/inventory");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer inventory-secret");
        request.setContent("request-body-value".getBytes(StandardCharsets.UTF_8));

        var response =
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
        var request = new MockHttpServletRequest("GET", "/api/v1/inventory");

        var response = handler.handleExceptionInternal(
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
}
