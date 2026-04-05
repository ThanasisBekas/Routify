package gr.routify.admin.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine in-process cache configuration for routify-admin-api.
 *
 * <h3>Cache: {@code activeWorkspaces}</h3>
 * <p>Single-entry cache holding the list of active workspaces (tenants) returned
 * by {@link gr.routify.admin.client.IdentityMessagingClient#listActiveWorkspaces()}.
 * This endpoint is called on every login page load (dropdown population) and
 * rarely changes — a 30-second TTL keeps it fresh while dramatically reducing
 * RabbitMQ RPC round-trips.
 *
 * <p><b>Invalidation:</b> Evicted explicitly on tenant create, update, suspend,
 * and reactivate operations (all go through {@code IdentityMessagingClient}).
 * The 30-second TTL serves as a safety net.
 */
@Configuration
public class CacheConfig {

    public static final String CACHE_ACTIVE_WORKSPACES = "activeWorkspaces";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(CACHE_ACTIVE_WORKSPACES);
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(1)
                .expireAfterWrite(30, TimeUnit.SECONDS)
                .recordStats());
        return manager;
    }
}

