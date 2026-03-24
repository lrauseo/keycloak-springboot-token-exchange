package br.com.lrs.example.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

/**
 * Security configuration for the Example Service.
 *
 * Key feature: Multi-realm JWT validation.
 *
 * The service accepts tokens issued by MULTIPLE Keycloak realms:
 *  - example-service-realm  → when a user authenticates directly against this service
 *  - gateway-realm          → when the API Gateway performs a token exchange
 *
 * The custom multiIssuerJwtDecoder() tries each configured realm's JWK set
 * in order until one successfully validates the token.
 */
@Slf4j
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final KeycloakProperties keycloakProperties;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Public endpoints — no authentication required
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/api/public/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/api-docs/**").permitAll()
                        // Everything else requires a valid JWT from any configured realm
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.decoder(multiIssuerJwtDecoder()))
                )
                .build();
    }

    /**
     * Creates a JwtDecoder that validates tokens from multiple Keycloak realms.
     *
     * For each incoming token, it tries every configured issuer's JWK set.
     * The first successful validation wins.
     * If no decoder accepts the token, a JwtException is thrown → 401 Unauthorized.
     */
    @Bean
    public JwtDecoder multiIssuerJwtDecoder() {
        List<String> issuers = keycloakProperties.getIssuers();

        if (issuers.isEmpty()) {
            throw new IllegalStateException(
                    "No Keycloak issuers configured. Set keycloak.issuers in application.yml");
        }

        log.info("Configuring multi-issuer JWT decoder for {} realm(s): {}", issuers.size(), issuers);

        List<JwtDecoder> decoders = issuers.stream()
                .map(issuer -> {
                    String jwkSetUri = issuer + "/protocol/openid-connect/certs";
                    log.debug("Registering JWK set URI: {}", jwkSetUri);
                    return (JwtDecoder) NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
                })
                .toList();

        // Return a composite decoder that tries each realm in sequence
        return token -> {
            JwtException lastException = null;
            for (JwtDecoder decoder : decoders) {
                try {
                    return decoder.decode(token);
                } catch (JwtException e) {
                    lastException = e;
                    log.trace("Decoder rejected token: {}", e.getMessage());
                }
            }
            log.warn("No configured issuer accepted the JWT token");
            throw new JwtException("JWT token rejected by all configured issuers", lastException);
        };
    }
}
