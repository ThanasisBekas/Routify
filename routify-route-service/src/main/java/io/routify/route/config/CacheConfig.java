package io.routify.route.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.routify.route.outbox.GatewaySnapshotCacheEvictor;
import io.routify.route.outbox.OutboxPoller;
import io.routify.route.service.RouteService;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine in-process cache configuration for routify-route-service.
 *
 * <h3>Cache: {@code gatewaySnapshot}</h3>
 * <p>Single-entry cache holding the full list of active routes with filters
 * returned by {@link RouteService#findAllActiveWithFilters()}.
 * This is the hottest query path — called by the gateway on every reload
 * and by the gateway snapshot RabbitMQ handler.
 *
 * <p><b>Invalidation:</b> Evicted via {@link GatewaySnapshotCacheEvictor} on every
 * successful outbox publish cycle ({@link OutboxPoller#pollAndPublish()}) — meaning
 * any route or filter mutation that reaches the outbox will clear the cache.
 * The 60-second TTL serves as a safety net for edge cases.
 *
 * <p><b>Size:</b> {@code maximumSize=1} — there is only one global snapshot
 * (not tenant-scoped; the gateway loads all active routes across all tenants).
 */
@Configuration
public class CacheConfig {

    public static final String CACHE_GATEWAY_SNAPSHOT = "gatewaySnapshot";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(CACHE_GATEWAY_SNAPSHOT);
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(1)
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .recordStats());
        return manager;
    }
}

