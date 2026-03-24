package br.com.lrs.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for Keycloak token exchange.
 * Bound from keycloak.token-exchange.* in application.yml
 */
@Data
@Component
@ConfigurationProperties(prefix = "keycloak.token-exchange")
public class TokenExchangeProperties {

    /** Enable or disable the token exchange filter */
    private boolean enabled = true;

    /** Base URL of the Keycloak server (e.g., http://keycloak:8080) */
    private String baseUrl = "http://localhost:8180";

    /**
     * The realm where the token exchange happens.
     * The incoming subject token (from example-service-realm) is exchanged
     * for a new access token issued by THIS realm (gateway-realm).
     */
    private String realm = "gateway-realm";

    /** Client ID with permission to perform token exchange */
    private String clientId = "gateway-client";

    /** Client secret for the exchange client */
    private String clientSecret = "gateway-client-secret";

    /**
     * Alias of the Identity Provider in the target realm that trusts the source
     * realm.
     * For this demo, it is the same as the source realm name.
     */
    private String subjectIssuer = "example-service-realm";
}
