package com.rentflow.converter;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import com.rentflow.util.KeycloakClaimNames;

public class InventoryAuthorityConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
    private static final Set<String> PERMISSIONS =
            Set.of("inventory:read", "inventory:list", "inventory:manage", "inventory:history", "inventory:reserve");

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>(new JwtGrantedAuthoritiesConverter().convert(jwt));
        if (jwt.getClaim(KeycloakClaimNames.RESOURCE_ACCESS) instanceof Map<?, ?> resources
                && resources.get("inventory-api") instanceof Map<?, ?> inventory
                && inventory.get(KeycloakClaimNames.ROLES) instanceof Collection<?> roles) {
            for (Object role : roles) {
                if (role instanceof String permission && PERMISSIONS.contains(permission)) {
                    authorities.add(new SimpleGrantedAuthority("PERM_" + permission));
                }
            }
        }
        return authorities;
    }
}
