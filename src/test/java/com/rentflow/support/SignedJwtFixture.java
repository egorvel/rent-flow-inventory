package com.rentflow.support;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.jwt.JoseHeaderNames;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.rentflow.converter.InventoryAuthorityConverter;
import com.rentflow.util.KeycloakClaimNames;
import com.sun.net.httpserver.HttpServer;

public final class SignedJwtFixture implements AutoCloseable {
    public static final SignedJwtFixture SHARED = new SignedJwtFixture();
    public static final List<String> STAFF =
            List.of("inventory:read", "inventory:list", "inventory:manage", "inventory:history");
    public static final List<String> CUSTOMER = List.of("inventory:read", "inventory:list");
    private final HttpServer server;
    private RSAKey key;
    private boolean available = true;
    private int fetches;

    public SignedJwtFixture() {
        try {
            key = new RSAKeyGenerator(2048)
                    .keyID(java.util.UUID.randomUUID().toString())
                    .generate();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/realms/test/protocol/openid-connect/certs", exchange -> {
                synchronized (this) {
                    fetches++;
                    byte[] body = (available ? new JWKSet(key.toPublicJWK()).toString() : "unavailable")
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(available ? 200 : 503, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                }
            });
            server.start();
        } catch (Exception error) {
            throw new IllegalStateException("Cannot start signing fixture", error);
        }
    }

    public String issuer() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/realms/test";
    }

    public String jwks() {
        return issuer() + "/protocol/openid-connect/certs";
    }

    public Map<String, Object> claims(String subject, String client, List<String> permissions) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put(JwtClaimNames.ISS, issuer());
        claims.put(JwtClaimNames.SUB, subject);
        claims.put(IdTokenClaimNames.AZP, client);
        claims.put(JwtClaimNames.AUD, List.of("inventory-api"));
        claims.put(JwtClaimNames.IAT, Instant.now().getEpochSecond());
        claims.put(JwtClaimNames.EXP, Instant.now().plusSeconds(300).getEpochSecond());
        claims.put(KeycloakClaimNames.TYP, "Bearer");
        claims.put(
                KeycloakClaimNames.RESOURCE_ACCESS,
                Map.of("inventory-api", Map.of(KeycloakClaimNames.ROLES, permissions)));
        return claims;
    }

    public String token(String subject, String client, List<String> permissions) {
        return token(claims(subject, client, permissions), JWSAlgorithm.RS256, "JWT");
    }

    public String staff() {
        return token("staff", "inventory-swagger", STAFF);
    }

    public String token(Map<String, Object> claims, JWSAlgorithm algorithm, String type) {
        try {
            JWSObject object = new JWSObject(
                    new JWSHeader.Builder(algorithm)
                            .type(type == null ? null : new JOSEObjectType(type))
                            .keyID(key.getKeyID())
                            .build(),
                    new Payload(JSONObjectUtils.toJSONString(claims)));
            object.sign(new RSASSASigner(key));
            return object.serialize();
        } catch (Exception error) {
            throw new IllegalStateException("Cannot sign test credential", error);
        }
    }

    public static void authenticateStaff() {
        Map<String, Object> claims = SHARED.claims("staff", "inventory-swagger", STAFF);
        claims.put(JwtClaimNames.IAT, Instant.now());
        claims.put(JwtClaimNames.EXP, Instant.now().plusSeconds(300));
        Jwt jwt = Jwt.withTokenValue("in-memory-service-test")
                .header(JoseHeaderNames.ALG, "RS256")
                .claims(values -> values.putAll(claims))
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt, new InventoryAuthorityConverter().convert(jwt)));
    }

    public synchronized void available(boolean available) {
        this.available = available;
    }

    public synchronized int fetches() {
        return fetches;
    }

    public synchronized void rotate() {
        try {
            key = new RSAKeyGenerator(2048)
                    .keyID(java.util.UUID.randomUUID().toString())
                    .generate();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
