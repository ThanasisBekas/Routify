package io.routify.route.service;

import io.routify.common.domain.TenantPlan;
import io.routify.route.client.IdentityServiceClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache of {@code tenantId → TenantPlan} mappings for quota enforcement.
 *
 * <p>Populated from:
 * <ol>
 *   <li>Bulk warmup at startup via {@link io.routify.route.service.TenantPlanCacheWarmer}</li>
 *   <li>{@code TENANT_EVENTS} Kafka topic (tenant create/update events)</li>
 *   <li>On-demand RPC fetch on cache miss (via {@link IdentityServiceClient})</li>
 * </ol>
 *
 * <p>On cache miss, the cache fetches the plan from identity-service via RabbitMQ RPC.
 * If the RPC also fails (identity-service unavailable), it falls back to
 * {@code TenantPlan.FREE} (most restrictive) to prevent quota bypass.
 */
@Slf4j
@Component
public class TenantPlanCache {

    private final ConcurrentHashMap<UUID, TenantPlan> cache = new ConcurrentHashMap<>();
    private final IdentityServiceClient identityServiceClient;

    public TenantPlanCache(IdentityServiceClient identityServiceClient) {
        this.identityServiceClient = identityServiceClient;
    }

    /**
     * Returns the cached plan for the given tenant.
     *
     * <p>On cache miss, performs a synchronous RPC to identity-service to fetch
     * the plan, caches the result, and returns it. If the RPC fails, falls back
     * to {@code TenantPlan.FREE} without caching so the next call retries.
     */
    public TenantPlan getPlan(UUID tenantId) {
        TenantPlan cached = cache.get(tenantId);
        if (cached != null) {
            return cached;
        }
        return fetchAndCache(tenantId);
    }

    /**
     * Updates the cached plan for a tenant. Called by the tenant event Kafka consumer.
     */
    public void put(UUID tenantId, TenantPlan plan) {
        cache.put(tenantId, plan);
        log.debug("TenantPlanCache: updated tenantId={} plan={}", tenantId, plan);
    }

    /**
     * Bulk-loads tenant plans into the cache. Called during startup warmup.
     *
     * @param plans map of tenantId → plan to load.
     * @return number of entries loaded.
     */
    public int putAll(Map<UUID, TenantPlan> plans) {
        cache.putAll(plans);
        log.info("TenantPlanCache: bulk-loaded {} tenant plans", plans.size());
        return plans.size();
    }

    /**
     * Removes a tenant from the cache (e.g. on suspend).
     */
    public void remove(UUID tenantId) {
        cache.remove(tenantId);
        log.debug("TenantPlanCache: removed tenantId={}", tenantId);
    }

    /**
     * Fetches the plan for a single tenant from identity-service, caches it,
     * and returns it. If the RPC fails, returns {@code TenantPlan.FREE} without
     * caching (so the next call retries).
     */
    private TenantPlan fetchAndCache(UUID tenantId) {
        log.info("TenantPlanCache: cache miss for tenantId={} — fetching from identity-service", tenantId);
        try {
            TenantPlan plan = identityServiceClient.fetchTenantPlan(tenantId);
            if (plan != null) {
                cache.put(tenantId, plan);
                log.info("TenantPlanCache: fetched and cached tenantId={} plan={}", tenantId, plan);
                return plan;
            }
        } catch (Exception e) {
            log.warn("TenantPlanCache: RPC failed for tenantId={} — falling back to FREE. Reason: {}",
                    tenantId, e.getMessage());
        }
        // Don't cache the fallback — next call will retry the RPC
        return TenantPlan.FREE;
    }
}

