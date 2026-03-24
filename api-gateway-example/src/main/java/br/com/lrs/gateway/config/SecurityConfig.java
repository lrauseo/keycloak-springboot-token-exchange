package br.com.lrs.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Gateway security configuration.
 *
 * The API Gateway does NOT validate JWT tokens itself — it simply:
 *  1) Exchanges the incoming token for a gateway-realm token (TokenExchangeGatewayFilterFactory)
 *  2) Forwards the new token to downstream microservices
 *  3) Each microservice validates the token against its own realm
 *
 * This is a common pattern in multi-realm Keycloak architectures.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(auth -> auth
                        // Actuator endpoints are always public
                        .pathMatchers("/actuator/**").permitAll()
                        // All other traffic passes through (JWT validation happens in microservices)
                        .anyExchange().permitAll()
                )
                .build();
    }
}
