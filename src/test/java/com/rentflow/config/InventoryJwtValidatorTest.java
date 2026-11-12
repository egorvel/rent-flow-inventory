package com.rentflow.config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.jwt.JoseHeaderNames;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;

import com.rentflow.util.KeycloakClaimNames;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryJwtValidatorTest {
    private static final String ISSUER = "https://issuer.example/realm";
    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    private final InventoryJwtValidator validator = new InventoryJwtValidator(ISSUER, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void requiresJwtHeaderIgnoringCaseAndKeepsBearerPayloadCaseSensitive() {
        for (String type : List.of("JWT", "jwt", "jWt")) {
            assertThat(validator
                            .validate(jwt(Map.of(), Map.of(JoseHeaderNames.TYP, type)))
                            .hasErrors())
                    .isFalse();
        }
        for (String type : List.of("", " ", "at+jwt", "application/jwt", "Bearer")) {
            assertThat(validator
                            .validate(jwt(Map.of(), Map.of(JoseHeaderNames.TYP, type)))
                            .hasErrors())
                    .isTrue();
        }
        Map<String, Object> absentType = new LinkedHashMap<>();
        absentType.put(JoseHeaderNames.TYP, null);
        assertThat(validator.validate(jwt(Map.of(), absentType)).hasErrors()).isTrue();
        assertThat(validator
                        .validate(jwt(Map.of(), Map.of(JoseHeaderNames.TYP, 123)))
                        .hasErrors())
                .isTrue();
        for (String type : List.of("bearer", "BEARER", "ID", "Refresh")) {
            assertThat(validator
                            .validate(jwt(Map.of(KeycloakClaimNames.TYP, type), Map.of()))
                            .hasErrors())
                    .isTrue();
        }
    }

    @Test
    void acceptsStillValidOlderTokensAndEnforcesExactClockSkewWithOptionalNotBefore() {
        // Issued two minutes ago and still valid: JwtIssuedAtValidator with 30s skew would reject this.
        assertThat(validator.validate(jwt(Map.of(), Map.of())).hasErrors()).isFalse();
        for (int seconds : List.of(29, 30, 31)) {
            assertThat(validator
                            .validate(jwt(Map.of(JwtClaimNames.IAT, NOW.plusSeconds(seconds)), Map.of()))
                            .hasErrors())
                    .isEqualTo(seconds > 30);
            assertThat(validator
                            .validate(jwt(Map.of(JwtClaimNames.EXP, NOW.minusSeconds(seconds)), Map.of()))
                            .hasErrors())
                    .isEqualTo(seconds > 30);
            assertThat(validator
                            .validate(jwt(Map.of(JwtClaimNames.NBF, NOW.plusSeconds(seconds)), Map.of()))
                            .hasErrors())
                    .isEqualTo(seconds > 30);
        }
        assertThat(validator
                        .validate(jwt(Map.of(JwtClaimNames.NBF, NOW.minusSeconds(100)), Map.of()))
                        .hasErrors())
                .isFalse();
    }

    @Test
    void preservesExactIssuerAudienceRepresentationsAndNonblankStringIdentities() {
        for (Object audience :
                List.of("inventory-api", List.of("inventory-api"), List.of("other-api", "inventory-api"))) {
            assertThat(validator
                            .validate(jwt(Map.of(JwtClaimNames.AUD, audience), Map.of()))
                            .hasErrors())
                    .isFalse();
        }
        for (Object audience : List.of(
                "Inventory-api", "other-api", List.of(), List.of("other-api"), List.of("inventory-api", 3), 3)) {
            assertThat(validator
                            .validate(jwt(Map.of(JwtClaimNames.AUD, audience), Map.of()))
                            .hasErrors())
                    .isTrue();
        }
        for (Object issuer :
                List.of(ISSUER + "/", ISSUER.toUpperCase(java.util.Locale.ROOT), java.net.URI.create(ISSUER))) {
            assertThat(validator
                            .validate(jwt(Map.of(JwtClaimNames.ISS, issuer), Map.of()))
                            .hasErrors())
                    .isTrue();
        }
        for (String claim : List.of(JwtClaimNames.SUB, IdTokenClaimNames.AZP)) {
            for (Object value : List.of("", " ", 3, List.of("subject"))) {
                assertThat(validator
                                .validate(jwt(Map.of(claim, value), Map.of()))
                                .hasErrors())
                        .isTrue();
            }
        }
    }

    @Test
    void rejectsMissingRequiredAndMalformedClaimsAsValidationFailures() {
        for (String claim : List.of(
                JwtClaimNames.ISS,
                JwtClaimNames.SUB,
                IdTokenClaimNames.AZP,
                JwtClaimNames.AUD,
                JwtClaimNames.IAT,
                JwtClaimNames.EXP,
                KeycloakClaimNames.TYP)) {
            Map<String, Object> absent = new LinkedHashMap<>();
            absent.put(claim, null);
            assertThat(validator.validate(jwt(absent, Map.of())).hasErrors()).isTrue();
        }
        for (String claim : List.of(JwtClaimNames.IAT, JwtClaimNames.EXP, JwtClaimNames.NBF)) {
            for (Object value : List.of("2026-11-09T12:00:00Z", 123)) {
                assertThat(validator
                                .validate(jwt(Map.of(claim, value), Map.of()))
                                .hasErrors())
                        .isTrue();
            }
        }
        for (Object roles : List.of(
                "wrong",
                Map.of("inventory-api", "wrong"),
                Map.of("inventory-api", Map.of(KeycloakClaimNames.ROLES, "wrong")),
                Map.of("inventory-api", Map.of(KeycloakClaimNames.ROLES, List.of(3))))) {
            assertThat(validator
                            .validate(jwt(Map.of(KeycloakClaimNames.RESOURCE_ACCESS, roles), Map.of()))
                            .hasErrors())
                    .isTrue();
        }
        Map<String, Object> noRoles = new LinkedHashMap<>();
        noRoles.put(KeycloakClaimNames.RESOURCE_ACCESS, null);
        assertThat(validator.validate(jwt(noRoles, Map.of())).hasErrors()).isFalse();
        assertThat(validator
                        .validate(jwt(
                                Map.of(
                                        KeycloakClaimNames.RESOURCE_ACCESS,
                                        Map.of("other-api", Map.of(KeycloakClaimNames.ROLES, List.of("unknown")))),
                                Map.of()))
                        .hasErrors())
                .isFalse();
    }

    private Jwt jwt(Map<String, Object> overrides, Map<String, Object> headerOverrides) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put(JwtClaimNames.ISS, ISSUER);
        claims.put(JwtClaimNames.SUB, "subject");
        claims.put(IdTokenClaimNames.AZP, "inventory-swagger");
        claims.put(JwtClaimNames.AUD, List.of("inventory-api"));
        claims.put(JwtClaimNames.IAT, NOW.minusSeconds(120));
        claims.put(JwtClaimNames.EXP, NOW.plusSeconds(180));
        claims.put(KeycloakClaimNames.TYP, "Bearer");
        claims.putAll(overrides);
        claims.values().removeIf(java.util.Objects::isNull);
        Map<String, Object> headers =
                new LinkedHashMap<>(Map.of(JoseHeaderNames.ALG, "RS256", JoseHeaderNames.TYP, "JWT"));
        headers.putAll(headerOverrides);
        headers.values().removeIf(java.util.Objects::isNull);
        return new Jwt(
                "unit-test",
                claims.get(JwtClaimNames.IAT) instanceof Instant issued ? issued : null,
                claims.get(JwtClaimNames.EXP) instanceof Instant expiry ? expiry : null,
                headers,
                claims);
    }
}
