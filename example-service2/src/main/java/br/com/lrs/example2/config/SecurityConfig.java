package br.com.lrs.example2.config;

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
 * Security for example-service2.
 *
 * This is a purely INTERNAL service — it only accepts tokens from gateway-realm.
 * Tokens from example-service-realm are REJECTED here.
 *
 * This is intentional: it demonstrates that internal services can enforce
 * a stricter trust boundary than public-facing services.
 *
 * The only way to reach this service correctly is:
 *   1. Client authenticates against example-service-realm → gets a token
 *   2. API Gateway exchanges it → gateway-realm token
 *   3. example-service receives the gateway-realm token, calls this service with it
 *   4. THIS service validates the gateway-realm token ✓
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
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/api-docs/**").permitAll()
                        // All /api/** requires a valid JWT — must come from gateway-realm
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.decoder(jwtDecoder()))
                )
                .build();
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        List<String> issuers = keycloakProperties.getIssuers();

        if (issuers.isEmpty()) {
            throw new IllegalStateException(
                    "No Keycloak issuers configured for example-service2. Set keycloak.issuers in application.yml");
        }

        log.info("example-service2 trusts {} issuer(s): {}", issuers.size(), issuers);

        List<JwtDecoder> decoders = issuers.stream()
                .map(issuer -> {
                    String jwkSetUri = issuer + "/protocol/openid-connect/certs";
                    log.debug("Registering JWK set: {}", jwkSetUri);
                    return (JwtDecoder) NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
                })
                .toList();

        return token -> {
            JwtException last = null;
            for (JwtDecoder decoder : decoders) {
                try {
                    return decoder.decode(token);
                } catch (JwtException e) {
                    last = e;
                }
            }
            log.warn("example-service2 rejected token — not issued by a trusted realm");
            throw new JwtException("Token rejected by example-service2 — only gateway-realm tokens accepted", last);
        };
    }
}
