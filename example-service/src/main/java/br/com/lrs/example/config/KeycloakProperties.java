package br.com.lrs.example.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the list of trusted Keycloak issuer URIs.
 * The service accepts JWT tokens from ALL configured issuers —
 * this is the "multi-realm" feature demonstrated in this portfolio.
 *
 * Configured via keycloak.issuers in application.yml:
 *   keycloak:
 *     issuers:
 *       - http://keycloak:8080/realms/example-service-realm  # direct login
 *       - http://keycloak:8080/realms/gateway-realm          # token exchanged by gateway
 */
@Data
@Component
@ConfigurationProperties(prefix = "keycloak")
public class KeycloakProperties {

    private List<String> issuers = new ArrayList<>();
}
