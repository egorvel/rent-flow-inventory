package com.rentflow.config;

import java.util.List;
import java.util.Set;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.rentflow.model.InventoryStatus;
import com.rentflow.model.InventoryStatusTransition;

public final class InventoryAuthorizationPolicy {
    private static final Set<String> HUMAN_CLIENTS = Set.of("inventory-swagger", "rentflow-pkce-check");

    public boolean canRead() {
        return canRead(current());
    }

    public boolean canList() {
        return canList(current());
    }

    public boolean canHistory() {
        return canHistory(current());
    }

    public boolean canManage() {
        return canManage(current());
    }

    public boolean canTransition(List<InventoryStatusTransition> transitions) {
        return canTransition(current(), transitions);
    }

    private static Authentication current() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    public boolean canRead(Authentication authentication) {
        return (human(authentication) || client(authentication, "pricing-service"))
                && permission(authentication, "read");
    }

    public boolean canList(Authentication authentication) {
        return human(authentication) && permission(authentication, "list");
    }

    public boolean canHistory(Authentication authentication) {
        return human(authentication) && permission(authentication, "history");
    }

    public boolean canManage(Authentication authentication) {
        return human(authentication) && permission(authentication, "manage");
    }

    public boolean canRequestTransition(Authentication authentication) {
        return canManage(authentication)
                || (client(authentication, "reservation-service") && permission(authentication, "reserve"));
    }

    public boolean canTransition(Authentication authentication, List<InventoryStatusTransition> transitions) {
        return canManage(authentication)
                || (client(authentication, "reservation-service")
                        && permission(authentication, "reserve")
                        && transitions != null
                        && !transitions.isEmpty()
                        && transitions.stream()
                                .allMatch(transition ->
                                        transition != null && transition.status() == InventoryStatus.RESERVED));
    }

    private boolean human(Authentication authentication) {
        return authentication instanceof JwtAuthenticationToken token
                && token.isAuthenticated()
                && HUMAN_CLIENTS.contains(token.getToken().getClaimAsString(IdTokenClaimNames.AZP));
    }

    private boolean client(Authentication authentication, String client) {
        return authentication instanceof JwtAuthenticationToken token
                && token.isAuthenticated()
                && client.equals(token.getToken().getClaimAsString(IdTokenClaimNames.AZP));
    }

    private boolean permission(Authentication authentication, String permission) {
        return authentication != null
                && authentication.getAuthorities().stream()
                        .anyMatch(authority -> ("PERM_inventory:" + permission).equals(authority.getAuthority()));
    }
}
