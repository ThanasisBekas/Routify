package io.routify.gateway.filter;

import io.routify.common.domain.TenantPlan;
import io.routify.common.event.DomainEvent;
import io.routify.common.event.KafkaTopics;
import io.routify.gateway.client.IdentityServiceClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache of {@code tenantId → TenantPlan} for gateway-layer request quota enforcement.
 *
 * <p>Populated from:
 * <ol>
 *   <li>Bulk warmup at startup via {@link GatewayTenantPlanCacheWarmer}</li>
 *   <li>{@code TENANT_EVENTS} Kafka topic (tenant create/update events)</li>
 *   <li>Async on-demand RPC fetch on cache miss</li>
 * </ol>
 *
 * <p>On cache miss, returns {@code TenantPlan.ENTERPRISE} (most permissive) so the
 * gateway never wrongly blocks legitimate traffic, and triggers an <strong>async</strong>
 * background RPC to identity-service to populate the cache for the next request.
 * The blocking RPC runs on {@code Schedulers.boundedElastic()} — never on the Netty event loop.
 */
@Slf4j
@Component
public class GatewayTenantPlanCache {

    private final ConcurrentHashMap<UUID, TenantPlan> cache = new ConcurrentHashMap<>();
    private final IdentityServiceClient identityServiceClient;

    /**
     * Tracks tenant IDs for which an async fetch is already in-flight,
     * preventing duplicate concurrent RPCs for the same tenant.
     */
    private final Set<UUID> pendingFetches = ConcurrentHashMap.newKeySet();

    public GatewayTenantPlanCache(IdentityServiceClient identityServiceClient) {
        this.identityServiceClient = identityServiceClient;
    }

    /**
     * Returns the cached plan for the given tenant.
     *
     * <p>On cache miss, returns {@code TenantPlan.ENTERPRISE} (most permissive)
     * and schedules an async RPC to identity-service to populate the cache.
     * This ensures the reactive gateway never blocks on an RPC in the hot path.
     */
    public TenantPlan getPlan(UUID tenantId) {
        TenantPlan cached = cache.get(tenantId);
        if (cached != null) {
            return cached;
        }
        // Cache miss — schedule async fetch and return permissive default
        scheduleAsyncFetch(tenantId);
        return TenantPlan.ENTERPRISE;
    }

    /**
     * Bulk-loads tenant plans into the cache. Called during startup warmup.
     *
     * @param plans map of tenantId → plan to load.
     * @return number of entries loaded.
     */
    public int putAll(Map<UUID, TenantPlan> plans) {
        cache.putAll(plans);
        log.info("GatewayTenantPlanCache: bulk-loaded {} tenant plans", plans.size());
        return plans.size();
    }

    @KafkaListener(
            topics = KafkaTopics.TENANT_EVENTS,
            groupId = "routify-gateway-tenant-events",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onTenantEvent(DomainEvent event, Acknowledgment ack) {
        try {
            switch (event) {
                case DomainEvent.TenantCreated tc -> {
                    TenantPlan plan = parsePlan(tc.plan());
                    if (plan != null) {
                        cache.put(tc.tenantId(), plan);
                        log.debug("GatewayTenantPlanCache: tenant created tenantId={} plan={}", tc.tenantId(), plan);
                    }
                }
                case DomainEvent.TenantUpdated tu -> {
                    TenantPlan plan = parsePlan(tu.plan());
                    if (plan != null) {
                        cache.put(tu.tenantId(), plan);
                        log.debug("GatewayTenantPlanCache: tenant updated tenantId={} plan={}", tu.tenantId(), plan);
                    }
                }
                case DomainEvent.TenantSuspended ts -> {
                    cache.remove(ts.tenantId());
                    log.debug("GatewayTenantPlanCache: tenant suspended tenantId={}", ts.tenantId());
                }
                default -> { /* ignore non-tenant events */ }
            }
        } catch (Exception e) {
            log.error("Failed to process tenant event in gateway: {}", e.getMessage(), e);
        }
        ack.acknowledge();
    }

    /**
     * Schedules a non-blocking background fetch for a single tenant's plan.
     * De-duplicates concurrent requests for the same tenant via {@link #pendingFetches}.
     */
    private void scheduleAsyncFetch(UUID tenantId) {
        if (identityServiceClient == null) {
            // No client available (e.g. in unit tests) — skip async fetch
            return;
        }
        if (!pendingFetches.add(tenantId)) {
            // Already fetching this tenant — skip duplicate
            return;
        }
        log.info("GatewayTenantPlanCache: cache miss for tenantId={} — scheduling async fetch", tenantId);
        Schedulers.boundedElastic().schedule(() -> {
            try {
                TenantPlan plan = identityServiceClient.fetchTenantPlan(tenantId);
                if (plan != null) {
                    cache.put(tenantId, plan);
                    log.info("GatewayTenantPlanCache: async fetch completed tenantId={} plan={}", tenantId, plan);
                } else {
                    log.warn("GatewayTenantPlanCache: async fetch returned null for tenantId={}", tenantId);
                }
            } catch (Exception e) {
                log.warn("GatewayTenantPlanCache: async fetch failed for tenantId={}: {}", tenantId, e.getMessage());
            } finally {
                pendingFetches.remove(tenantId);
            }
        });
    }

    private TenantPlan parsePlan(String plan) {
        if (plan == null || plan.isBlank()) return null;
        try {
            return TenantPlan.valueOf(plan.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown plan '{}' — ignoring", plan);
            return null;
        }
    }
}

