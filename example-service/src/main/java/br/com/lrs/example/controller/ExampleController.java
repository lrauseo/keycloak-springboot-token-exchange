package br.com.lrs.example.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Example REST controller demonstrating:
 * - Public endpoints (no auth)
 * - Protected endpoints (JWT required — accepted from multiple realms)
 * - Token introspection endpoint (shows which realm issued the token)
 * - Chained call: this service calls example-service2 propagating the same JWT
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Example", description = "Example endpoints for portfolio demonstration")
@RequiredArgsConstructor
@Slf4j
public class ExampleController {

        private final RestTemplate restTemplate;

        // ─── Public endpoints ─────────────────────────────────────────────────────

        @GetMapping("/public/hello")
        @Operation(summary = "Public hello", description = "No authentication required")
        public Map<String, Object> publicHello() {
                return Map.of(
                                "message", "Hello from Example Service!",
                                "endpoint", "public — no authentication required",
                                "timestamp", Instant.now().toString());
        }

        @GetMapping("/public/info")
        @Operation(summary = "Service info", description = "Returns service metadata — no auth needed")
        public Map<String, Object> serviceInfo() {
                return Map.of(
                                "service", "example-service",
                                "version", "1.0.0",
                                "description", "Keycloak token exchange portfolio demo",
                                "features", new String[] {
                                                "Multi-realm JWT validation",
                                                "Eureka service discovery",
                                                "Chained service-to-service calls"
                                },
                                "timestamp", Instant.now().toString());
        }

        // ─── Protected endpoints ───────────────────────────────────────────────────

        @GetMapping("/protected/hello")
        @Operation(summary = "Protected hello", description = "Requires a valid JWT from example-service-realm OR gateway-realm", security = @SecurityRequirement(name = "BearerAuth"))
        public Map<String, Object> protectedHello(@AuthenticationPrincipal Jwt jwt) {
                String username = jwt.getClaimAsString("preferred_username");
                String realm = extractRealm(jwt.getIssuer() != null ? jwt.getIssuer().toString() : "");

                return Map.of(
                                "message", "Hello, " + (username != null ? username : "authenticated user") + "!",
                                "endpoint", "protected — JWT validated",
                                "tokenIssuedBy", realm,
                                "timestamp", Instant.now().toString());
        }

        @GetMapping("/protected/token-info")
        @Operation(summary = "Token introspection", description = "Returns decoded JWT claims — useful to see which realm issued the token", security = @SecurityRequirement(name = "BearerAuth"))
        public Map<String, Object> tokenInfo(@AuthenticationPrincipal Jwt jwt) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("subject", jwt.getSubject());
                info.put("issuer", jwt.getIssuer() != null ? jwt.getIssuer().toString() : null);
                info.put("realmExtracted", extractRealm(
                                jwt.getIssuer() != null ? jwt.getIssuer().toString() : ""));
                info.put("username", jwt.getClaimAsString("preferred_username"));
                info.put("email", jwt.getClaimAsString("email"));
                info.put("issuedAt", jwt.getIssuedAt() != null ? jwt.getIssuedAt().toString() : null);
                info.put("expiresAt", jwt.getExpiresAt() != null ? jwt.getExpiresAt().toString() : null);
                info.put("scopes", jwt.getClaimAsString("scope"));
                info.put("realmAccess", jwt.getClaimAsMap("realm_access"));
                info.put("azp", jwt.getClaimAsString("azp")); // "authorized party" = the client that issued the token
                return info;
        }

        // ─── Chained call ─────────────────────────────────────────────────────────

        /**
         * Demonstrates the full service-chain flow:
         *
         * Client → Gateway (token exchange) → example-service → example-service2
         * ↓ (same gateway-realm token)
         * Client ← Gateway ←─────────────── example-service ←── example-service2
         *
         * The key insight: after the Gateway exchanges the token ONCE at the boundary,
         * the same gateway-realm JWT propagates through the entire internal chain
         * without further exchanges.
         */
        @GetMapping("/protected/chained")
        @Operation(summary = "Chained service call (full round-trip)", description = "example-service calls example-service2 propagating the same JWT. "
                        +
                        "Response shows the token as seen at each hop.", security = @SecurityRequirement(name = "BearerAuth"))
        public Map<String, Object> chained(
                        @AuthenticationPrincipal Jwt jwt,
                        @RequestHeader("Authorization") String authorizationHeader) {

                String realm = extractRealm(jwt.getIssuer() != null ? jwt.getIssuer().toString() : "");
                String username = jwt.getClaimAsString("preferred_username");

                log.debug("Chained call initiated by user '{}' with token from realm '{}'", username, realm);

                // Step 1 — build the outgoing request with the SAME JWT (no new exchange
                // needed)
                HttpHeaders headers = new HttpHeaders();
                headers.set(HttpHeaders.AUTHORIZATION, authorizationHeader);
                HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

                // Step 2 — call example-service2 via Eureka (load-balanced)
                Object service2Response;
                try {
                        service2Response = restTemplate.exchange(
                                        "http://example-service2/api/internal/hello",
                                        HttpMethod.GET,
                                        requestEntity,
                                        Map.class).getBody();
                        log.debug("example-service2 responded successfully");
                } catch (RestClientException e) {
                        log.warn("Could not reach example-service2: {}", e.getMessage());
                        service2Response = Map.of(
                                        "error", "example-service2 unreachable",
                                        "reason", e.getMessage());
                }

                // Step 3 — assemble combined response showing each integration step
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("flow", "Client → Gateway → example-service → example-service2");
                response.put("gatewayStep", Map.of(
                                "action", "Token exchange: example-service-realm → gateway-realm",
                                "note", "Performed transparently by TokenExchangeGatewayFilterFactory"));
                response.put("service1Step", Map.of(
                                "service", "example-service",
                                "tokenIssuedBy", realm,
                                "user", username != null ? username : "unknown",
                                "note", "Received the exchanged token and propagated it downstream"));
                response.put("service2Step", service2Response);
                response.put("timestamp", Instant.now().toString());
                return response;
        }

        // ─── Helpers ──────────────────────────────────────────────────────────────

        /**
         * Extracts the realm name from a Keycloak issuer URI.
         * Example: "http://keycloak:8080/realms/gateway-realm" → "gateway-realm"
         */
        private String extractRealm(String issuerUri) {
                if (issuerUri == null || issuerUri.isBlank())
                        return "unknown";
                int idx = issuerUri.lastIndexOf("/realms/");
                return idx >= 0 ? issuerUri.substring(idx + 8) : issuerUri;
        }
}
