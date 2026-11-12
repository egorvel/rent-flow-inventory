package com.rentflow.converter;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenIntrospectionClaimNames;
import org.springframework.security.oauth2.jwt.JoseHeaderNames;
import org.springframework.security.oauth2.jwt.Jwt;

import com.rentflow.util.KeycloakClaimNames;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryAuthorityConverterTest {
    @Test
    void mapsOnlyOwnAllowlistedRolesAndKeepsScopesDistinct() {
        Jwt jwt = Jwt.withTokenValue("unit-test")
                .header(JoseHeaderNames.ALG, "RS256")
                .claim(
                        KeycloakClaimNames.REALM_ACCESS,
                        Map.of(KeycloakClaimNames.ROLES, List.of("STAFF", "inventory:manage")))
                .claim(
                        KeycloakClaimNames.RESOURCE_ACCESS,
                        Map.of(
                                "inventory-api",
                                Map.of(KeycloakClaimNames.ROLES, List.of("inventory:read", "unknown")),
                                "other-api",
                                Map.of(KeycloakClaimNames.ROLES, List.of("inventory:manage"))))
                .claim(OAuth2TokenIntrospectionClaimNames.SCOPE, "inventory:manage openid")
                .build();
        assertThat(new InventoryAuthorityConverter().convert(jwt))
                .extracting(authority -> authority.getAuthority())
                .containsExactlyInAnyOrder("PERM_inventory:read", "SCOPE_inventory:manage", "SCOPE_openid");
        Jwt noRoles = Jwt.withTokenValue("unit-test")
                .header(JoseHeaderNames.ALG, "RS256")
                .subject("subject")
                .build();
        assertThat(new InventoryAuthorityConverter().convert(noRoles)).isEmpty();
    }
}
