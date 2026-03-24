package br.com.lrs.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class GatewayConfig {

    /**
     * WebClient used by the TokenExchangeGatewayFilterFactory
     * to call Keycloak's token endpoint.
     */
    @Bean
    public WebClient webClient() {
        return WebClient.builder().build();
    }
}
