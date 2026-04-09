package io.routify.route.service;

import io.routify.common.domain.TenantPlan;
import io.routify.common.event.QueryResponse;
import io.routify.route.client.IdentityServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Warms the {@link TenantPlanCache} on application startup by querying
 * routify-identity-service for all active tenant→plan mappings via RabbitMQ RPC.
 *
 * <p>Without this warmup, the cache defaults every unknown tenant to
 * {@code TenantPlan.FREE} after a service restart — incorrectly blocking
 * tenants on higher plans from creating routes/filters that exceed the FREE quota.
 *
 * <p>Failure is non-fatal: if identity-service is not yet available, the cache
 * will self-heal as Kafka {@code TENANT_EVENTS} arrive. A warning is logged so
 * operators can investigate startup ordering issues.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantPlanCacheWarmer {

    private final IdentityServiceClient identityServiceClient;
    private final TenantPlanCache tenantPlanCache;

    @EventListener(ApplicationReadyEvent.class)
    public void warmCache() {
        try {
            log.info("TenantPlanCacheWarmer: fetching active tenant plans from identity-service...");
            QueryResponse.TenantPlansList response = identityServiceClient.fetchTenantPlans();

            if (response == null || response.entries() == null || response.entries().isEmpty()) {
                log.warn("TenantPlanCacheWarmer: identity-service returned empty tenant plans — "
                        + "cache will self-heal via Kafka TENANT_EVENTS");
                return;
            }

            Map<UUID, TenantPlan> plans = response.entries().stream()
                    .collect(Collectors.toMap(
                            QueryResponse.TenantPlansList.TenantPlanEntry::tenantId,
                            e -> parsePlan(e.plan()),
                            (a, b) -> b  // in case of duplicates, last wins
                    ));

            int loaded = tenantPlanCache.putAll(plans);
            log.info("TenantPlanCacheWarmer: successfully loaded {} tenant plans", loaded);

        } catch (Exception e) {
            log.warn("TenantPlanCacheWarmer: failed to warm cache from identity-service — "
                    + "cache will self-heal via Kafka TENANT_EVENTS. Reason: {}", e.getMessage());
        }
    }

    private TenantPlan parsePlan(String plan) {
        if (plan == null || plan.isBlank()) return TenantPlan.FREE;
        try {
            return TenantPlan.valueOf(plan.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("TenantPlanCacheWarmer: unknown plan '{}' — defaulting to FREE", plan);
            return TenantPlan.FREE;
        }
    }
}

