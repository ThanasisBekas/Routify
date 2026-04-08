package io.routify.route.service;

import io.routify.common.domain.TenantPlan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache of {@code tenantId → TenantPlan} mappings for quota enforcement.
 *
 * <p>Populated from {@code TENANT_EVENTS} Kafka topic (tenant create/update events).
 * On cache miss, defaults to {@code TenantPlan.FREE} (most restrictive) to prevent
 * quota bypass for unknown tenants.
 *
 * <p>The cache is eventually consistent — plan changes propagate within the Kafka
 * consumer lag window (typically < 1 second).
 */
@Slf4j
@Component
public class TenantPlanCache {

    private final ConcurrentHashMap<UUID, TenantPlan> cache = new ConcurrentHashMap<>();

    /**
     * Returns the cached plan for the given tenant, or {@code TenantPlan.FREE} if unknown.
     */
    public TenantPlan getPlan(UUID tenantId) {
        return cache.getOrDefault(tenantId, TenantPlan.FREE);
    }

    /**
     * Updates the cached plan for a tenant. Called by the tenant event Kafka consumer.
     */
    public void put(UUID tenantId, TenantPlan plan) {
        cache.put(tenantId, plan);
        log.debug("TenantPlanCache: updated tenantId={} plan={}", tenantId, plan);
    }

    /**
     * Removes a tenant from the cache (e.g. on suspend).
     */
    public void remove(UUID tenantId) {
        cache.remove(tenantId);
        log.debug("TenantPlanCache: removed tenantId={}", tenantId);
    }
}

