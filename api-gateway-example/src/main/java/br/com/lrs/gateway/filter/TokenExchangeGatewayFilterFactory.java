package br.com.lrs.gateway.filter;

import br.com.lrs.gateway.config.TokenExchangeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Custom Spring Cloud Gateway filter that performs Keycloak Token Exchange.
 *
 * Flow:
 *   1. Extracts the Bearer token from the incoming request (e.g., from example-service-realm)
 *   2. Calls Keycloak's token exchange endpoint to get a new token (gateway-realm)
 *   3. Replaces the Authorization header in the outgoing request with the new token
 *   4. The downstream service (example-service) validates the new token
 *
 * This filter is registered as "TokenExchange" in application.yml routes.
 * Usage in YAML:
 *   filters:
 *     - TokenExchange
 *
 * Keycloak Token Exchange grant type:
 *   POST /realms/{realm}/protocol/openid-connect/token
 *   grant_type=urn:ietf:params:oauth:grant-type:token-exchange
 *   subject_token={incoming_token}
 *   subject_token_type=urn:ietf:params:oauth:token-type:access_token
 *   requested_token_type=urn:ietf:params:oauth:token-type:access_token
 *   client_id={gateway-client}
 *   client_secret={secret}
 */
@Slf4j
@Component
public class TokenExchangeGatewayFilterFactory
        extends AbstractGatewayFilterFactory<TokenExchangeGatewayFilterFactory.Config> {

    private final TokenExchangeProperties properties;
    private final WebClient webClient;

    public TokenExchangeGatewayFilterFactory(TokenExchangeProperties properties, WebClient webClient) {
        super(Config.class);
        this.properties = properties;
        this.webClient = webClient;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {

            // If token exchange is disabled, pass request through unchanged
            if (!properties.isEnabled()) {
                log.debug("Token exchange is disabled — passing request through");
                return chain.filter(exchange);
            }

            String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

            // No Bearer token present — let the downstream service handle it (it will reject unauthorized)
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                log.debug("No Bearer token found — skipping token exchange");
                return chain.filter(exchange);
            }

            String subjectToken = authHeader.substring(7);
            log.debug("Performing token exchange for request: {} {}",
                    exchange.getRequest().getMethod(), exchange.getRequest().getPath());

            return exchangeToken(subjectToken)
                    .flatMap(newToken -> {
                        log.debug("Token exchange successful — request forwarded with gateway-realm token");
                        var mutatedRequest = exchange.getRequest().mutate()
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + newToken)
                                .build();
                        return chain.filter(exchange.mutate().request(mutatedRequest).build());
                    })
                    .onErrorResume(e -> {
                        // Graceful degradation: if exchange fails, forward with original token.
                        // The downstream service will reject it if it doesn't accept the original realm.
                        log.warn("Token exchange failed — forwarding original token. Reason: {}", e.getMessage());
                        return chain.filter(exchange);
                    });
        };
    }

    /**
     * Calls Keycloak's token exchange endpoint.
     * Returns the new access_token from the gateway-realm.
     */
    private Mono<String> exchangeToken(String subjectToken) {
        String tokenUrl = String.format("%s/realms/%s/protocol/openid-connect/token",
                properties.getBaseUrl(), properties.getRealm());

        log.debug("Calling token exchange at: {}", tokenUrl);

        return webClient.post()
                .uri(tokenUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters
                        .fromFormData("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange")
                        .with("subject_token", subjectToken)
                        .with("subject_token_type", "urn:ietf:params:oauth:token-type:access_token")
                        .with("requested_token_type", "urn:ietf:params:oauth:token-type:access_token")
                        .with("client_id", properties.getClientId())
                        .with("client_secret", properties.getClientSecret()))
                .retrieve()
                .bodyToMono(Map.class)
                .map(response -> {
                    @SuppressWarnings("unchecked")
                    String token = (String) response.get("access_token");
                    if (token == null) {
                        throw new RuntimeException(
                                "Token exchange response missing 'access_token'. Response: " + response);
                    }
                    return token;
                });
    }

    /**
     * Per-route configuration class.
     * Currently empty — can be extended to support per-route target realm overrides.
     */
    public static class Config {
        // Future: private String targetRealm;
    }
}
