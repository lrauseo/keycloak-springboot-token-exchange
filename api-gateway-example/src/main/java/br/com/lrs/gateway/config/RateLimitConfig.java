package br.com.lrs.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

@Configuration
public class RateLimitConfig {

    /**
     * Redis-backed rate limiter:
     *   - replenishRate = 10 requests/sec (steady state)
     *   - burstCapacity  = 20 requests    (burst allowance)
     *   - requestedTokens = 1             (cost per request)
     */
    @Bean
    public RedisRateLimiter redisRateLimiter() {
        return new RedisRateLimiter(10, 20, 1);
    }

    /**
     * Determines the rate-limit key per request.
     * Priority:
     *   1. First 50 chars of the Bearer token (user-level rate limiting)
     *   2. Remote IP address (fallback for unauthenticated requests)
     */
    @Bean
    public KeyResolver keyResolver() {
        return exchange -> {
            String authHeader = exchange.getRequest()
                    .getHeaders()
                    .getFirst("Authorization");

            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                // Token-based key: use the first 50 characters as a stable identifier
                String token = authHeader.substring(7);
                return Mono.just(token.substring(0, Math.min(token.length(), 50)));
            }

            // Fallback: IP-based key
            if (exchange.getRequest().getRemoteAddress() != null) {
                return Mono.just(
                        exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                );
            }

            return Mono.just("anonymous");
        };
    }
}
