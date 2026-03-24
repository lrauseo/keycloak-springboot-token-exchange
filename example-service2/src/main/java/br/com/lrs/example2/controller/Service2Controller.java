package br.com.lrs.example2.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Internal endpoints for example-service2.
 *
 * This service is meant to be called by example-service (service-to-service),
 * NOT directly by external clients. The gateway-realm JWT forwarded by
 * example-service is validated here.
 *
 * /api/internal/** → service-to-service (requires gateway-realm JWT)
 * /api/public/**   → no auth (health/info)
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Service2", description = "Internal downstream service endpoints")
public class Service2Controller {

    // ─── Public ───────────────────────────────────────────────────────────────

    @GetMapping("/public/info")
    @Operation(summary = "Service2 public info", description = "No auth required")
    public Map<String, Object> publicInfo() {
        return Map.of(
                "service", "example-service2",
                "role", "internal-downstream",
                "description", "Called by example-service after token exchange — never directly by clients",
                "port", 8082,
                "timestamp", Instant.now().toString()
        );
    }

    // ─── Internal (service-to-service) ────────────────────────────────────────

    @GetMapping("/internal/hello")
    @Operation(
            summary = "Internal hello from service2",
            description = "Called by example-service. Validates that the token is from gateway-realm.",
            security = @SecurityRequirement(name = "BearerAuth")
    )
    public Map<String, Object> internalHello(@AuthenticationPrincipal Jwt jwt) {
        String username = jwt.getClaimAsString("preferred_username");
        String realm = extractRealm(jwt.getIssuer().toString());

        return Map.of(
                "message", "Hello from example-service2! (internal service)",
                "receivedFrom", "example-service",
                "tokenIssuedBy", realm,
                "originalUser", username != null ? username : "unknown",
                "note", "This token was exchanged by the API Gateway — " +
                         "the original user's realm is NOT visible here anymore",
                "timestamp", Instant.now().toString()
        );
    }

    @GetMapping("/internal/token-info")
    @Operation(
            summary = "Token info at service2",
            description = "Shows the JWT claims as seen by the internal service — issuer should be gateway-realm",
            security = @SecurityRequirement(name = "BearerAuth")
    )
    public Map<String, Object> internalTokenInfo(@AuthenticationPrincipal Jwt jwt) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("service", "example-service2");
        info.put("subject", jwt.getSubject());
        info.put("issuer", jwt.getIssuer() != null ? jwt.getIssuer().toString() : null);
        info.put("realmExtracted", extractRealm(
                jwt.getIssuer() != null ? jwt.getIssuer().toString() : ""));
        info.put("username", jwt.getClaimAsString("preferred_username"));
        info.put("issuedAt", jwt.getIssuedAt() != null ? jwt.getIssuedAt().toString() : null);
        info.put("expiresAt", jwt.getExpiresAt() != null ? jwt.getExpiresAt().toString() : null);
        info.put("azp", jwt.getClaimAsString("azp"));
        info.put("realmAccess", jwt.getClaimAsMap("realm_access"));
        info.put("note", "Only gateway-realm tokens are accepted by this service");
        return info;
    }

    private String extractRealm(String issuerUri) {
        if (issuerUri == null || issuerUri.isBlank()) return "unknown";
        int idx = issuerUri.lastIndexOf("/realms/");
        return idx >= 0 ? issuerUri.substring(idx + 8) : issuerUri;
    }
}
