package io.routify.gateway.filter;

import io.routify.common.domain.TenantPlan;
import io.routify.common.event.QueryResponse;
import io.routify.gateway.client.IdentityServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Warms the {@link GatewayTenantPlanCache} on application startup by querying
 * routify-identity-service for all active tenant→plan mappings via RabbitMQ RPC.
 *
 * <p>Without this warmup, the cache defaults every unknown tenant to
 * {@code TenantPlan.FREE} after a gateway restart — incorrectly applying
 * FREE-tier request size limits to higher-plan tenants.
 *
 * <p>The blocking RPC call is acceptable during startup because the Netty
 * event loop is not yet serving traffic (same pattern as
 * {@code DynamicRouteRefreshListener#onApplicationReady()}).
 *
 * <p>Failure is non-fatal: if identity-service is not yet available, the cache
 * will self-heal as Kafka {@code TENANT_EVENTS} arrive.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayTenantPlanCacheWarmer {

    private final IdentityServiceClient identityServiceClient;
    private final GatewayTenantPlanCache tenantPlanCache;

    @EventListener(ApplicationReadyEvent.class)
    public void warmCache() {
        try {
            log.info("GatewayTenantPlanCacheWarmer: fetching active tenant plans from identity-service...");
            QueryResponse.TenantPlansList response = identityServiceClient.fetchTenantPlans();

            if (response == null || response.entries() == null || response.entries().isEmpty()) {
                log.warn("GatewayTenantPlanCacheWarmer: identity-service returned empty tenant plans — "
                        + "cache will self-heal via Kafka TENANT_EVENTS");
                return;
            }

            Map<UUID, TenantPlan> plans = response.entries().stream()
                    .collect(Collectors.toMap(
                            QueryResponse.TenantPlansList.TenantPlanEntry::tenantId,
                            e -> parsePlan(e.plan()),
                            (a, b) -> b
                    ));

            int loaded = tenantPlanCache.putAll(plans);
            log.info("GatewayTenantPlanCacheWarmer: successfully loaded {} tenant plans", loaded);

        } catch (Exception e) {
            log.warn("GatewayTenantPlanCacheWarmer: failed to warm cache from identity-service — "
                    + "cache will self-heal via Kafka TENANT_EVENTS. Reason: {}", e.getMessage());
        }
    }

    private TenantPlan parsePlan(String plan) {
        if (plan == null || plan.isBlank()) return TenantPlan.FREE;
        try {
            return TenantPlan.valueOf(plan.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("GatewayTenantPlanCacheWarmer: unknown plan '{}' — defaulting to FREE", plan);
            return TenantPlan.FREE;
        }
    }
}

