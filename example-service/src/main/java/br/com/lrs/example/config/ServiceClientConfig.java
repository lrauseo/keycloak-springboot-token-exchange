package br.com.lrs.example.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Configures a load-balanced RestTemplate.
 *
 * The @LoadBalanced annotation enables client-side load balancing via Eureka:
 * instead of a real hostname, you can use the Eureka service name as the host.
 *
 * Example:
 *   restTemplate.getForObject("http://example-service2/api/internal/hello", ...)
 *                                    ↑
 *                       Eureka service name — resolved to actual IP by Spring Cloud
 */
@Configuration
public class ServiceClientConfig {

    @Bean
    @LoadBalanced
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
