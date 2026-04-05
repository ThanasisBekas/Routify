package io.routify.gateway.ratelimit;

import io.routify.common.web.RoutifyHeaders;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

/**
 * Rate limiter key resolver configurations.
 *
 * <p>Spring Cloud Gateway's RequestRateLimiter filter requires a {@link KeyResolver} bean
 * to determine the rate limiting key for each request. We provide multiple implementations
 * to support different rate limiting strategies.
 */
@Configuration
public class RateLimiterKeyResolverConfig {

    /**
     * Rate limit by client IP address.
     * Default resolver for public-facing routes.
     */
    @Bean
    @Primary
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.just(
                exchange.getRequest().getRemoteAddress() != null
                        ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                        : "unknown"
        );
    }

    /**
     * Rate limit by authenticated user ID.
     * Used for routes with JWT auth — provides per-user rate limiting.
     */
    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> {
            String userId = exchange.getRequest().getHeaders().getFirst(RoutifyHeaders.AUTH_USER_ID);
            return Mono.just(userId != null ? "user:" + userId : "anonymous");
        };
    }

    /**
     * Rate limit by tenant ID.
     * Ensures one tenant's traffic doesn't starve others — fair multi-tenancy.
     */
    @Bean
    public KeyResolver tenantKeyResolver() {
        return exchange -> {
            String tenantId = exchange.getRequest().getHeaders().getFirst(RoutifyHeaders.TENANT_ID);
            return Mono.just(tenantId != null ? "tenant:" + tenantId : "unknown-tenant");
        };
    }

    /**
     * Rate limit by API Key.
     * For routes using API key authentication.
     */
    @Bean
    public KeyResolver apiKeyResolver() {
        return exchange -> {
            String apiKey = exchange.getRequest().getHeaders().getFirst(RoutifyHeaders.API_KEY);
            if (apiKey == null) {
                apiKey = exchange.getRequest().getQueryParams().getFirst("apiKey");
            }
            return Mono.just(apiKey != null ? "apikey:" + apiKey.hashCode() : "no-key");
        };
    }

    /**
     * Composite key — tenant + user combination.
     * Most granular — allows per-user-per-tenant rate limiting.
     */
    @Bean
    public KeyResolver tenantUserKeyResolver() {
        return exchange -> {
            String tenantId = exchange.getRequest().getHeaders().getFirst(RoutifyHeaders.TENANT_ID);
            String userId   = exchange.getRequest().getHeaders().getFirst(RoutifyHeaders.AUTH_USER_ID);
            String key = "%s:%s".formatted(
                    tenantId != null ? tenantId : "unknown",
                    userId   != null ? userId   : "anonymous");
            return Mono.just(key);
        };
    }
}

