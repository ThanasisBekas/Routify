package gr.routify.identity.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine in-process cache configuration for routify-identity-service.
 *
 * <h3>Cache: {@code users}</h3>
 * <p>Caches user query results (by-ID and paginated lists). Capacity: 500 entries,
 * TTL: 120 seconds. Evicted on any user mutation (create/update/delete) via
 * {@code @CacheEvict(allEntries = true)} — safe because user writes are rare
 * (admin-only actions via Kafka commands).
 *
 * <h3>Cache: {@code tenants}</h3>
 * <p>Caches tenant query results (by-ID and paginated lists). Capacity: 100 entries,
 * TTL: 300 seconds. Evicted on any tenant mutation (create/update/suspend/reactivate).
 * Tenant mutations are very rare, so aggressive caching is appropriate.
 */
@Configuration
public class CacheConfig {

    public static final String CACHE_USERS   = "users";
    public static final String CACHE_TENANTS = "tenants";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setAllowNullValues(false);
        manager.registerCustomCache(CACHE_USERS,
                Caffeine.newBuilder()
                        .maximumSize(500)
                        .expireAfterWrite(120, TimeUnit.SECONDS)
                        .recordStats()
                        .build());
        manager.registerCustomCache(CACHE_TENANTS,
                Caffeine.newBuilder()
                        .maximumSize(100)
                        .expireAfterWrite(300, TimeUnit.SECONDS)
                        .recordStats()
                        .build());
        return manager;
    }
}

