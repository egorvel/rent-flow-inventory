package com.rentflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.OAuth2ProtectedResourceMetadataClaimNames;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.nimbusds.jose.JWSAlgorithm;
import com.rentflow.converter.InventoryAuthorityConverter;
import com.rentflow.model.InventoryItem;
import com.rentflow.model.InventoryStatus;
import com.rentflow.model.InventoryStatusTransition;
import com.rentflow.repository.InventoryRepository;
import com.rentflow.repository.InventoryStatusHistoryRepository;
import com.rentflow.repository.InventoryStatusTransitionRequestRepository;
import com.rentflow.service.IdempotencyCleanupService;
import com.rentflow.service.IdempotentStatusTransitionService;
import com.rentflow.service.InventoryService;
import com.rentflow.support.EcsLogCapture;
import com.rentflow.support.PostgresIntegrationTest;
import com.rentflow.support.SignedJwtFixture;
import com.rentflow.util.KeycloakClaimNames;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "spring.kafka.listener.auto-startup=false"})
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
class SecurityIT extends PostgresIntegrationTest {
    private static final String PATH = "/api/v1/inventory/status";
    private static final String BODY = "[{\"serialNumber\":\"SEC-A\",\"status\":\"RESERVED\"}]";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private InventoryRepository inventory;

    @Autowired
    private InventoryStatusHistoryRepository history;

    @MockitoSpyBean
    private InventoryStatusTransitionRequestRepository requests;

    @Autowired
    private InventoryService service;

    @Autowired
    private IdempotentStatusTransitionService transitions;

    @Autowired
    private IdempotencyCleanupService cleanup;

    @Autowired
    private JwtDecoder decoder;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Environment environment;

    @Autowired
    private MeterRegistry metrics;

    @Autowired
    private ObjectMapper mapper;

    @BeforeEach
    void reset() {
        requests.deleteAllInBatch();
        history.deleteAllInBatch();
        inventory.deleteAllInBatch();
        inventory.saveAndFlush(new InventoryItem("SEC-A", "Drill", "Security test", InventoryStatus.AVAILABLE));
        clearInvocations(requests);
    }

    @Test
    void rejectsMissingMalformedDuplicateAndNonHeaderCredentialsWithSafeChallenges() throws Exception {
        mvc.perform(get("/api/v1/inventory").accept(MediaType.TEXT_HTML))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.violations").doesNotExist())
                .andExpect(header().doesNotExist("Set-Cookie"));
        for (String credential : List.of("Bearer bad", "Basic abc", "Bearer", "Bearer a,b")) {
            mvc.perform(get("/api/v1/inventory").header("Authorization", credential))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer error=\"invalid_token\""));
        }
        mvc.perform(get("/api/v1/inventory").header("Authorization", "Bearer bad", "Bearer bad"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/inventory").param("access_token", SignedJwtFixture.SHARED.staff()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/inventory")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("access_token", SignedJwtFixture.SHARED.staff()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(requests);
    }

    @Test
    void validatesRealSignaturesAlgorithmsTokenTypesAndAllRequiredClaims() throws Exception {
        Map<String, Object> baseline =
                SignedJwtFixture.SHARED.claims("staff", "inventory-swagger", SignedJwtFixture.STAFF);
        List<Map<String, Object>> invalid = new ArrayList<>();
        for (String required : List.of(
                JwtClaimNames.ISS,
                JwtClaimNames.SUB,
                IdTokenClaimNames.AZP,
                JwtClaimNames.AUD,
                JwtClaimNames.IAT,
                JwtClaimNames.EXP,
                KeycloakClaimNames.TYP)) {
            Map<String, Object> claims = new java.util.LinkedHashMap<>(baseline);
            claims.remove(required);
            invalid.add(claims);
        }
        for (Map.Entry<String, Object> value : Map.<String, Object>ofEntries(
                        Map.entry(JwtClaimNames.ISS, "https://wrong.example/realm"),
                        Map.entry(JwtClaimNames.SUB, " "),
                        Map.entry(IdTokenClaimNames.AZP, 3),
                        Map.entry(JwtClaimNames.AUD, List.of("pricing-api")),
                        Map.entry(KeycloakClaimNames.TYP, "ID"),
                        Map.entry(
                                JwtClaimNames.IAT,
                                java.time.Instant.now().plusSeconds(90).getEpochSecond()),
                        Map.entry(
                                JwtClaimNames.EXP,
                                java.time.Instant.now().minusSeconds(90).getEpochSecond()),
                        Map.entry(
                                JwtClaimNames.NBF,
                                java.time.Instant.now().plusSeconds(90).getEpochSecond()),
                        Map.entry(KeycloakClaimNames.RESOURCE_ACCESS, "not-an-object"))
                .entrySet()) {
            Map<String, Object> claims = new java.util.LinkedHashMap<>(baseline);
            claims.put(value.getKey(), value.getValue());
            invalid.add(claims);
        }
        for (Map<String, Object> claims : invalid) {
            mvc.perform(get("/api/v1/inventory")
                            .header(
                                    "Authorization",
                                    "Bearer " + SignedJwtFixture.SHARED.token(claims, JWSAlgorithm.RS256, "JWT")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.detail").value("Valid bearer authentication is required."));
        }
        for (String type : List.of("at+jwt", "Refresh")) {
            mvc.perform(get("/api/v1/inventory")
                            .header(
                                    "Authorization",
                                    "Bearer " + SignedJwtFixture.SHARED.token(baseline, JWSAlgorithm.RS256, type)))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/v1/inventory")
                        .header(
                                "Authorization",
                                "Bearer " + SignedJwtFixture.SHARED.token(baseline, JWSAlgorithm.RS512, "JWT")))
                .andExpect(status().isUnauthorized());
        try (SignedJwtFixture foreign = new SignedJwtFixture()) {
            mvc.perform(get("/api/v1/inventory")
                            .header("Authorization", "Bearer " + foreign.token(baseline, JWSAlgorithm.RS256, "JWT")))
                    .andExpect(status().isUnauthorized());
        }
        baseline.put(JwtClaimNames.AUD, "inventory-api");
        mvc.perform(get("/api/v1/inventory")
                        .header(
                                "Authorization",
                                "Bearer " + SignedJwtFixture.SHARED.token(baseline, JWSAlgorithm.RS256, "JWT")))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsMissingOrNullIssuedAtOnShortLivedSignedTokens() throws Exception {
        java.time.Instant now = java.time.Instant.now();
        Map<String, Object> baseline =
                SignedJwtFixture.SHARED.claims("staff", "inventory-swagger", SignedJwtFixture.STAFF);
        baseline.put(JwtClaimNames.IAT, now.minusSeconds(60).getEpochSecond());
        baseline.put(JwtClaimNames.EXP, now.plusSeconds(20).getEpochSecond());
        mvc.perform(get("/api/v1/inventory")
                        .header(
                                "Authorization",
                                "Bearer " + SignedJwtFixture.SHARED.token(baseline, JWSAlgorithm.RS256, "JWT")))
                .andExpect(status().isOk());

        // A short expiry keeps Spring's synthesized iat within the allowed future skew.
        Map<String, Object> missingIssuedAt = new java.util.LinkedHashMap<>(baseline);
        missingIssuedAt.remove(JwtClaimNames.IAT);
        Map<String, Object> nullIssuedAt = new java.util.LinkedHashMap<>(baseline);
        nullIssuedAt.put(JwtClaimNames.IAT, null);
        for (Map<String, Object> claims : List.of(missingIssuedAt, nullIssuedAt)) {
            mvc.perform(get("/api/v1/inventory")
                            .header(
                                    "Authorization",
                                    "Bearer " + SignedJwtFixture.SHARED.token(claims, JWSAlgorithm.RS256, "JWT")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer error=\"invalid_token\""))
                    .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                    .andExpect(jsonPath("$.detail").value("Valid bearer authentication is required."));
        }
    }

    @Test
    void acceptsCaseInsensitiveRequiredJwtHeaderAndPreservesPayloadTypeAndOlderTokenValidation() throws Exception {
        Map<String, Object> claims =
                SignedJwtFixture.SHARED.claims("staff", "inventory-swagger", SignedJwtFixture.STAFF);
        claims.put(JwtClaimNames.IAT, java.time.Instant.now().minusSeconds(120).getEpochSecond());
        claims.put(JwtClaimNames.EXP, java.time.Instant.now().plusSeconds(180).getEpochSecond());
        for (String type : List.of("JWT", "jwt", "jWt")) {
            mvc.perform(get("/api/v1/inventory")
                            .header(
                                    "Authorization",
                                    "Bearer " + SignedJwtFixture.SHARED.token(claims, JWSAlgorithm.RS256, type)))
                    .andExpect(status().isOk());
        }
        List<String> invalidHeaders = new ArrayList<>(List.of("", " ", "at+jwt", "application/jwt"));
        invalidHeaders.add(null);
        for (String type : invalidHeaders) {
            mvc.perform(get("/api/v1/inventory")
                            .header(
                                    "Authorization",
                                    "Bearer " + SignedJwtFixture.SHARED.token(claims, JWSAlgorithm.RS256, type)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer error=\"invalid_token\""))
                    .andExpect(jsonPath("$.detail").value("Valid bearer authentication is required."));
        }
        for (String type : List.of("bearer", "BEARER", "ID", "Refresh")) {
            claims.put(KeycloakClaimNames.TYP, type);
            mvc.perform(get("/api/v1/inventory")
                            .header(
                                    "Authorization",
                                    "Bearer " + SignedJwtFixture.SHARED.token(claims, JWSAlgorithm.RS256, "jwt")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        }
    }

    @Test
    void enforcesHumanMachineAndRoutePermissionMatrixEvenWithAccidentalOvergrants() throws Exception {
        String staff = SignedJwtFixture.SHARED.staff();
        String customer = token("customer", "inventory-swagger", SignedJwtFixture.CUSTOMER);
        String pricing = token("pricing", "pricing-service", List.of("inventory:read"));
        String reservation = token("reservation", "reservation-service", List.of("inventory:reserve"));
        for (String reader : List.of(staff, customer, pricing)) {
            mvc.perform(get("/api/v1/inventory/SEC-A").header("Authorization", "Bearer " + reader))
                    .andExpect(status().isOk());
        }
        for (String client : List.of("pricing-service", "reservation-service", "unapproved-tool")) {
            String overgrant = token("overgrant", client, SignedJwtFixture.STAFF);
            mvc.perform(get("/api/v1/inventory-history").header("Authorization", "Bearer " + overgrant))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/inventory").header("Authorization", "Bearer " + overgrant))
                    .andExpect(status().isForbidden());
            mvc.perform(delete("/api/v1/inventory/SEC-A").header("Authorization", "Bearer " + overgrant))
                    .andExpect(status().isForbidden());
        }
        for (String denied : List.of(customer, pricing, reservation)) {
            mvc.perform(post("/api/v1/inventory")
                            .header("Authorization", "Bearer " + denied)
                            .content("{bad-json"))
                    .andExpect(status().isForbidden());
            mvc.perform(put("/api/v1/inventory/SEC-A").header("Authorization", "Bearer " + denied))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/inventory-history").header("Authorization", "Bearer " + denied))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/inventory-history").header("Authorization", "Bearer " + staff))
                .andExpect(status().isOk());
        for (String path : List.of("/api/v1/inventory", "/api/v1/inventory/SEC-A", "/not-an-operation")) {
            mvc.perform(head(path).header("Authorization", "Bearer " + staff)).andExpect(status().isForbidden());
            mvc.perform(options(path).header("Authorization", "Bearer " + staff))
                    .andExpect(status().isForbidden());
        }
        for (String serial : List.of("SEC-A", "MISSING")) {
            mvc.perform(get("/api/v1/inventory/" + serial).header("Authorization", "Bearer " + reservation))
                    .andExpect(status().isForbidden());
        }
        for (String client : List.of("inventory-swagger", "rentflow-pkce-check", "other")) {
            send(token("human", client, List.of("inventory:reserve")), UUID.randomUUID(), BODY)
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void bindsEachRouteToItsSpecificCapabilityBeforeBodyValidation() throws Exception {
        Map<String, String> readRoutes = Map.of(
                "/api/v1/inventory/SEC-A", "inventory:read",
                "/api/v1/inventory", "inventory:list",
                "/api/v1/inventory-history", "inventory:history");
        Map<HttpMethod, String> writeRoutes = Map.of(
                HttpMethod.POST, "/api/v1/inventory",
                HttpMethod.PUT, "/api/v1/inventory/SEC-A",
                HttpMethod.PATCH, PATH);
        for (String permission : List.of("inventory:read", "inventory:list", "inventory:history", "inventory:manage")) {
            String credential = token("limited-human", "inventory-swagger", List.of(permission));
            for (Map.Entry<String, String> route : readRoutes.entrySet()) {
                mvc.perform(get(route.getKey()).header("Authorization", "Bearer " + credential))
                        .andExpect(status().is(permission.equals(route.getValue()) ? 200 : 403));
            }
            for (Map.Entry<HttpMethod, String> route : writeRoutes.entrySet()) {
                mvc.perform(request(route.getKey(), route.getValue())
                                .header("Authorization", "Bearer " + credential)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{bad-json"))
                        .andExpect(status().is(permission.equals("inventory:manage") ? 400 : 403));
            }
            mvc.perform(delete("/api/v1/inventory/DELETE-MISSING").header("Authorization", "Bearer " + credential))
                    .andExpect(status().is(permission.equals("inventory:manage") ? 404 : 403));
        }
        verifyNoInteractions(requests);
        assertThat(inventory.existsById("SEC-A")).isTrue();
        assertThat(history.count()).isZero();
    }

    @Test
    void rejectsForbiddenReservationTargetsBeforeReplayLocksOrItemMutation() throws Exception {
        String reservation =
                token("reservation", "reservation-service", List.of("inventory:reserve", "inventory:manage"));
        UUID key = UUID.randomUUID();
        send(reservation, key, BODY).andExpect(status().isNoContent());
        clearInvocations(requests);
        send(reservation, key, BODY.replace("RESERVED", "AVAILABLE"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Idempotency-Replayed"));
        send(
                        reservation,
                        key,
                        "[ {\"serialNumber\":\"SEC-A\",\"status\":\"RESERVED\"}, {\"serialNumber\":\"SEC-B\",\"status\":\"RENTED\"} ]")
                .andExpect(status().isForbidden());
        verifyNoInteractions(requests);
        assertThat(history.count()).isOne();
        assertThat(inventory.findById("SEC-A").orElseThrow().getStatus()).isEqualTo(InventoryStatus.RESERVED);
        send(reservation, UUID.randomUUID(), BODY).andExpect(status().isConflict());
        send(reservation, UUID.randomUUID(), BODY.replace("SEC-A", "MISSING")).andExpect(status().isNotFound());
    }

    @Test
    void isolatesEqualKeysAcrossSubjectsAndRechecksPermissionsBeforeSavedResults() throws Exception {
        UUID key = UUID.randomUUID();
        String personA = token("person-a", "inventory-swagger", SignedJwtFixture.STAFF);
        send(personA, key, BODY)
                .andExpect(status().isNoContent())
                .andExpect(header().string("Idempotency-Replayed", "false"));
        send(token("person-b", "inventory-swagger", SignedJwtFixture.STAFF), key, BODY)
                .andExpect(status().isConflict())
                .andExpect(header().string("Idempotency-Replayed", "false"));
        send(token("person-a", "rentflow-pkce-check", SignedJwtFixture.STAFF), key, BODY)
                .andExpect(status().isNoContent())
                .andExpect(header().string("Idempotency-Replayed", "true"));
        clearInvocations(requests);
        send(token("person-a", "inventory-swagger", SignedJwtFixture.CUSTOMER), key, BODY)
                .andExpect(status().isForbidden());
        verifyNoInteractions(requests);
        assertThat(requests.count()).isEqualTo(2);
        assertThat(history.count()).isOne();
    }

    @Test
    void enforcesActualServiceProxiesAndKeepsCleanupIndependentOfHumanContext() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> service.get("SEC-A"))
                .isInstanceOfAny(
                        AccessDeniedException.class,
                        org.springframework.security.authentication.AuthenticationCredentialsNotFoundException.class);
        org.springframework.security.oauth2.jwt.Jwt jwt =
                decoder.decode(token("reservation", "reservation-service", List.of("inventory:reserve")));
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt, new InventoryAuthorityConverter().convert(jwt)));
        assertThatThrownBy(() -> transitions.transition(
                        UUID.randomUUID(), List.of(new InventoryStatusTransition("SEC-A", InventoryStatus.RENTED))))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(requests);
        SecurityContextHolder.clearContext();
        assertThat(cleanup.deleteChunk()).isZero();
    }

    @Test
    void cleansOnlyExpiredCompositeRowsAndNeverReplaysLegacyResults() throws Exception {
        UUID key = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inventory.inventory_status_transition_requests
                  (caller_scope,idempotency_key,fingerprint,http_status,outcome,recorded_at,expires_at)
                VALUES ('legacy:unauthenticated',?,repeat('a',64),204,'{"status":204,"failedItems":[]}'::jsonb,
                  statement_timestamp()-interval '8 days',statement_timestamp()-interval '1 day')
                """, key);
        send(SignedJwtFixture.SHARED.staff(), key, BODY)
                .andExpect(status().isNoContent())
                .andExpect(header().string("Idempotency-Replayed", "false"));
        assertThat(requests.count()).isEqualTo(2);
        assertThat(cleanup.deleteChunk()).isOne();
        assertThat(requests.count()).isOne();
        send(SignedJwtFixture.SHARED.staff(), key, BODY).andExpect(header().string("Idempotency-Replayed", "true"));
    }

    @Test
    void deniesDoNotLeakCredentialsOrBodiesAndUseOnlyBoundedMetricLabels() throws Exception {
        try (EcsLogCapture logs = new EcsLogCapture("com.rentflow")) {
            mvc.perform(post("/api/v1/inventory")
                            .header("Authorization", "Bearer synthetic-private-credential")
                            .content("synthetic-private-body"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content()
                            .string(org.hamcrest.Matchers.not(
                                    org.hamcrest.Matchers.containsString("synthetic-private"))));
            assertThat(logs.events())
                    .isNotEmpty()
                    .allSatisfy(event -> assertThat(
                                    EcsLogCapture.encode(event, environment).toString())
                            .doesNotContain("synthetic-private", "stack_trace"));
        }
        assertThat(metrics.get("inventory.security.denials").meters()).allSatisfy(meter -> {
            // Composite registry meters have the two local labels; exported children also carry common tags.
            assertThat(meter.getId().getTags())
                    .extracting(tag -> tag.getKey())
                    .contains("reason", "surface")
                    .allMatch(key -> java.util.Set.of("reason", "surface", "service", "environment")
                            .contains(key));
            assertThat(meter.getId().getTag("reason")).isIn("authentication", "authorization");
            assertThat(meter.getId().getTag("surface")).isIn("application", "management");
        });
    }

    private String token(String subject, String client, List<String> permissions) {
        return SignedJwtFixture.SHARED.token(subject, client, permissions);
    }

    @Test
    void isolatesRealManagementListenerAndDisablesDocs() throws Exception {
        int app = environment.getRequiredProperty("local.server.port", Integer.class);
        int management = environment.getRequiredProperty("local.management.port", Integer.class);
        try (java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient()) {
            for (String path : List.of("/livez", "/readyz")) {
                java.net.http.HttpResponse<String> response = actual(client, app, path, false, null);
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).isEqualTo("{\"status\":\"UP\"}");
            }
            for (String path : List.of(
                    "/actuator/health",
                    "/actuator/health/liveness",
                    "/actuator/health/readiness",
                    "/actuator/prometheus")) {
                assertThat(actual(client, management, path, false, null).statusCode())
                        .isEqualTo(200);
            }
            for (String path : List.of(
                    "/actuator/env",
                    "/actuator",
                    "/api/v1/inventory",
                    "/v3/api-docs",
                    "/readyz",
                    "/.well-known/oauth-protected-resource",
                    "/.well-known/oauth-protected-resource/api")) {
                java.net.http.HttpResponse<String> response =
                        actual(client, management, path, true, "localhost:" + app);
                assertThat(response.statusCode()).isEqualTo(403);
                assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
            }
            for (String path :
                    List.of("/actuator/prometheus", "/v3/api-docs", "/swagger-ui.html", "/.well-known/unrelated")) {
                assertThat(actual(client, app, path, false, "localhost:" + management)
                                .statusCode())
                        .isEqualTo(401);
                assertThat(actual(client, app, path, true, "localhost:" + management)
                                .statusCode())
                        .isEqualTo(403);
            }
        }
    }

    @Test
    void publishesAccuratePublicMetadataUsingRequestUrlsAndNativeResourcePaths() throws Exception {
        int app = environment.getRequiredProperty("local.server.port", Integer.class);
        try (java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient()) {
            for (String suffix : List.of("", "/", "/api", "/api/v1/inventory", "/equipment%20catalogue")) {
                java.net.http.HttpResponse<String> response = actual(
                        client,
                        app,
                        "/.well-known/oauth-protected-resource" + suffix + "?ignored=value",
                        false,
                        "untrusted.example:9999");
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
                assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
                assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
                assertThat(mapper.readValue(response.body(), Map.class))
                        .isEqualTo(expectedMetadata("http://127.0.0.1:" + app + suffix));
            }
        }
        for (String origin : List.of("http://inventory.example.test:8087", "https://inventory.example.test:8443")) {
            mvc.perform(get(origin + "/.well-known/oauth-protected-resource/api")
                            .queryParam("access_token", "ignored-metadata-query-marker"))
                    .andExpect(status().isOk())
                    .andExpect(content().json(mapper.writeValueAsString(expectedMetadata(origin + "/api"))));
        }
        verifyNoInteractions(requests);
    }

    @Test
    void publicMetadataDoesNotAuthenticateCredentialsOrContactUnavailableProvider() throws Exception {
        int app = environment.getRequiredProperty("local.server.port", Integer.class);
        int fetches = SignedJwtFixture.SHARED.fetches();
        SignedJwtFixture.SHARED.available(false);
        try (java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient()) {
            for (List<String> credentials : List.of(
                    List.<String>of(),
                    List.of("Bearer malformed"),
                    List.of("Basic invalid"),
                    List.of("Bearer first", "Bearer second"),
                    List.of("Bearer " + SignedJwtFixture.SHARED.staff()))) {
                java.net.http.HttpRequest.Builder request = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://127.0.0.1:" + app + "/.well-known/oauth-protected-resource"));
                for (String credential : credentials) {
                    request.header("Authorization", credential);
                }
                java.net.http.HttpResponse<String> response =
                        client.send(request.GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(mapper.readValue(response.body(), Map.class))
                        .isEqualTo(expectedMetadata("http://127.0.0.1:" + app));
            }
            assertThat(SignedJwtFixture.SHARED.fetches()).isEqualTo(fetches);
        } finally {
            SignedJwtFixture.SHARED.available(true);
        }
        verifyNoInteractions(requests);
    }

    @Test
    void metadataNonGetMethodsRetainNormalAuthenticationAndDenyRules() throws Exception {
        for (HttpMethod method : List.of(HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.POST)) {
            mvc.perform(request(method, "/.well-known/oauth-protected-resource"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(request(method, "/.well-known/oauth-protected-resource")
                            .header("Authorization", "Bearer " + SignedJwtFixture.SHARED.staff()))
                    .andExpect(status().isForbidden());
        }
    }

    private Map<String, Object> expectedMetadata(String resourceUrl) {
        return Map.of(
                OAuth2ProtectedResourceMetadataClaimNames.RESOURCE,
                resourceUrl,
                OAuth2ProtectedResourceMetadataClaimNames.AUTHORIZATION_SERVERS,
                List.of(SignedJwtFixture.SHARED.issuer()),
                OAuth2ProtectedResourceMetadataClaimNames.BEARER_METHODS_SUPPORTED,
                List.of("header"),
                OAuth2ProtectedResourceMetadataClaimNames.TLS_CLIENT_CERTIFICATE_BOUND_ACCESS_TOKENS,
                false);
    }

    private java.net.http.HttpResponse<String> actual(
            java.net.http.HttpClient client, int port, String path, boolean authenticated, String forwardedHost)
            throws Exception {
        java.net.http.HttpRequest.Builder request =
                java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + path));
        if (authenticated) {
            request.header("Authorization", "Bearer " + SignedJwtFixture.SHARED.staff());
        }
        if (forwardedHost != null) {
            request.header("X-Forwarded-Host", forwardedHost)
                    .header("X-Forwarded-Port", forwardedHost.substring(forwardedHost.indexOf(':') + 1));
        }
        return client.send(request.GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    private ResultActions send(String credential, UUID key, String body) throws Exception {
        return mvc.perform(patch(PATH)
                .header("Authorization", "Bearer " + credential)
                .header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
