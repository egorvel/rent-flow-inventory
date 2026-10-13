package com.rentflow.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.filter.ServerHttpObservationFilter;
import org.springframework.web.servlet.HandlerMapping;

import com.rentflow.converter.InventoryConverter;
import com.rentflow.dto.InventoryStatusTransitionFailureDTO;
import com.rentflow.dto.InventoryStatusTransitionProblemResponse;
import com.rentflow.dto.ProblemResponse;
import com.rentflow.model.InventoryStatus;
import com.rentflow.model.InventoryStatusTransitionOutcome;
import com.rentflow.support.EcsLogCapture;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler =
            new ApiExceptionHandler(io.micrometer.observation.ObservationRegistry.NOOP);

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
    void returnsTheStableBatchProblemAndPreservesIndividualFailures() {
        MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/api/v1/inventory/status");
        InventoryStatusTransitionOutcome.Failure missing = new InventoryStatusTransitionOutcome.Failure(
                0,
                "MISSING",
                InventoryStatus.RESERVED,
                "INVENTORY_ITEM_NOT_FOUND",
                "Inventory item 'MISSING' was not found.");
        InventoryStatusTransitionOutcome.Failure conflict = new InventoryStatusTransitionOutcome.Failure(
                2,
                "DRILL-001",
                InventoryStatus.AVAILABLE,
                "INVALID_INVENTORY_STATUS_TRANSITION",
                "Inventory item 'DRILL-001' cannot transition from RENTED to AVAILABLE.");
        for (List<InventoryStatusTransitionOutcome.Failure> failures :
                List.of(List.of(missing), List.of(missing, conflict))) {
            boolean hasConflict = failures.size() == 2;
            InventoryStatusTransitionProblemResponse response =
                    new InventoryConverter().toResponse(InventoryStatusTransitionOutcome.rejected(failures));
            assertThat(response)
                    .isEqualTo(new InventoryStatusTransitionProblemResponse(
                            hasConflict
                                    ? "urn:rentflow:problem:invalid-inventory-status-transition"
                                    : "urn:rentflow:problem:inventory-item-not-found",
                            hasConflict ? "Invalid inventory status transition" : "Inventory item not found",
                            hasConflict ? 409 : 404,
                            "No inventory statuses were changed.",
                            "/api/v1/inventory/status",
                            hasConflict ? "INVALID_INVENTORY_STATUS_TRANSITION" : "INVENTORY_ITEM_NOT_FOUND",
                            failures.stream()
                                    .map(failure -> new InventoryStatusTransitionFailureDTO(
                                            failure.index(),
                                            failure.serialNumber(),
                                            failure.status(),
                                            failure.code(),
                                            failure.message()))
                                    .toList()));
        }
    }

    @Test
    void retainsInternalFailureDetailsWhileKeepingPublicErrorsGeneric(CapturedOutput output) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/inventory/DRILL-001");

        ResponseEntity<ProblemResponse> response = handler.handleUnexpected(
                new IllegalStateException("boom", new IllegalArgumentException("invalid equipment state")),
                new ServletWebRequest(request));

        assertThat(output)
                .contains("Unexpected failure handling request")
                .contains("DRILL-001", "boom", "invalid equipment state")
                .contains("java.lang.IllegalStateException")
                .contains(
                        "at com.rentflow.controller.ApiExceptionHandlerTest.retainsInternalFailureDetailsWhileKeepingPublicErrorsGeneric");
        assertThat(response.getBody().detail()).isEqualTo("An unexpected error occurred.");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recordsAndLogsUnexpectedFailuresFromBothHandlerPaths(boolean frameworkFailure) {
        ObservationRegistry registry = mock(ObservationRegistry.class);
        Observation observation = mock(Observation.class);
        when(registry.getCurrentObservation()).thenReturn(observation);
        ApiExceptionHandler observedHandler = new ApiExceptionHandler(registry);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/inventory/DRILL-001");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/inventory/{serialNumber}");
        ServerRequestObservationContext context =
                new ServerRequestObservationContext(request, new MockHttpServletResponse());
        request.setAttribute(ServerHttpObservationFilter.CURRENT_OBSERVATION_CONTEXT_ATTRIBUTE, context);
        IllegalStateException failure = new IllegalStateException("boom", new IllegalArgumentException("cause detail"));

        try (EcsLogCapture logs = new EcsLogCapture(ApiExceptionHandler.class.getName())) {
            ResponseEntity<?> response = frameworkFailure
                    ? observedHandler.handleExceptionInternal(
                            failure,
                            "internal detail",
                            HttpHeaders.EMPTY,
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            new ServletWebRequest(request))
                    : observedHandler.handleUnexpected(failure, new ServletWebRequest(request));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(response.getBody())
                    .isEqualTo(new ProblemResponse(
                            "urn:rentflow:problem:internal-error",
                            "Internal server error",
                            500,
                            "An unexpected error occurred.",
                            "/api/v1/inventory/DRILL-001",
                            "INTERNAL_ERROR",
                            java.util.List.of()));
            verify(observation).error(failure);
            assertThat(context.getError()).isSameAs(failure);
            assertThat(logs.events()).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.ERROR);
                JsonNode json = EcsLogCapture.encode(event, new MockEnvironment());
                assertThat(json.path("message").asText()).isEqualTo("Unexpected failure handling request");
                assertThat(json.path("method").asText()).isEqualTo("GET");
                assertThat(json.path("route").asText()).isEqualTo("/api/v1/inventory/{serialNumber}");
                assertThat(json.path("path").asText()).isEqualTo("/api/v1/inventory/DRILL-001");
                assertThat(json.path("exceptionType").asText())
                        .isEqualTo(failure.getClass().getName());
                assertThat(json.path("error").path("stack_trace").asText())
                        .contains("boom", "cause detail", "recordsAndLogsUnexpectedFailuresFromBothHandlerPaths");
            });
        }
    }

    @Test
    void usesUnmatchedRouteWhenNoHandlerPatternIsAvailable() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/inventory");
        try (EcsLogCapture logs = new EcsLogCapture(ApiExceptionHandler.class.getName())) {
            handler.handleUnexpected(new IllegalStateException("boom"), new ServletWebRequest(request));
            assertThat(logs.events())
                    .singleElement()
                    .satisfies(event -> assertThat(EcsLogCapture.encode(event, new MockEnvironment())
                                    .path("route")
                                    .asText())
                            .isEqualTo("unmatched"));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 409})
    void expectedClientErrorsDoNotRecordOrLogAnUnexpectedFailure(int status) {
        ObservationRegistry registry = mock(ObservationRegistry.class);
        Observation observation = mock(Observation.class);
        when(registry.getCurrentObservation()).thenReturn(observation);
        ApiExceptionHandler observedHandler = new ApiExceptionHandler(registry);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/inventory");
        ServerRequestObservationContext context =
                new ServerRequestObservationContext(request, new MockHttpServletResponse());
        request.setAttribute(ServerHttpObservationFilter.CURRENT_OBSERVATION_CONTEXT_ATTRIBUTE, context);
        try (EcsLogCapture logs = new EcsLogCapture(ApiExceptionHandler.class.getName())) {
            ResponseEntity<Object> response = observedHandler.handleExceptionInternal(
                    new IllegalArgumentException("expected rejection"),
                    null,
                    HttpHeaders.EMPTY,
                    HttpStatus.valueOf(status),
                    new ServletWebRequest(request));
            assertThat(response.getStatusCode().value()).isEqualTo(status);
            verifyNoInteractions(observation);
            assertThat(context.getError()).isNull();
            assertThat(logs.events()).isEmpty();
        }
    }
}
