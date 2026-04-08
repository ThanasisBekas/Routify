package io.routify.gateway.ratelimit;

import io.routify.gateway.filter.ratelimit.RateLimitKeyResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

/**
 * Rate limiter key resolver configurations for the SCG built-in {@code RequestRateLimiter} filter.
 *
 * <p>All beans delegate to the shared {@link RateLimitKeyResolver} component so that
 * key resolution logic is maintained in a single place and shared across all three
 * rate limiter paths (fixed window, sliding window, and SCG token bucket).
 */
@Configuration
@RequiredArgsConstructor
public class RateLimiterKeyResolverConfig {

    private final RateLimitKeyResolver rateLimitKeyResolver;

    /** Rate limit by client IP address — default resolver. */
    @Bean
    @Primary
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.just(rateLimitKeyResolver.resolve(exchange, "IP"));
    }

    /** Rate limit by authenticated user ID. */
    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> Mono.just(rateLimitKeyResolver.resolve(exchange, "USER"));
    }

    /** Rate limit by tenant ID. */
    @Bean
    public KeyResolver tenantKeyResolver() {
        return exchange -> Mono.just(rateLimitKeyResolver.resolve(exchange, "TENANT"));
    }

    /** Rate limit by API key. */
    @Bean
    public KeyResolver apiKeyResolver() {
        return exchange -> Mono.just(rateLimitKeyResolver.resolve(exchange, "API_KEY"));
    }

    /** Composite key — tenant + user combination. */
    @Bean
    public KeyResolver tenantUserKeyResolver() {
        return exchange -> Mono.just(rateLimitKeyResolver.resolve(exchange, "TENANT_USER"));
    }

    /** Rate limit by route ID — new strategy. */
    @Bean
    public KeyResolver routeKeyResolver() {
        return exchange -> Mono.just(rateLimitKeyResolver.resolve(exchange, "ROUTE"));
    }
}

