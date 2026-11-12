package com.rentflow.util;

/** Keycloak-specific access-token claim and nested field names. */
public final class KeycloakClaimNames {
    /** Payload token type, distinct from the JOSE header type. */
    public static final String TYP = "typ";

    public static final String RESOURCE_ACCESS = "resource_access";
    public static final String REALM_ACCESS = "realm_access";
    public static final String ROLES = "roles";

    private KeycloakClaimNames() {}
}
