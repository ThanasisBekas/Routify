package io.routify.gateway.filter;

import io.routify.common.domain.TenantPlan;
import io.routify.common.event.DomainEvent;
import io.routify.common.event.KafkaTopics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache of {@code tenantId → TenantPlan} for gateway-layer request quota enforcement.
 *
 * <p>Populated from {@code TENANT_EVENTS} Kafka topic. On cache miss, defaults to
 * {@code TenantPlan.FREE} (most restrictive) to prevent quota bypass.
 */
@Slf4j
@Component
public class GatewayTenantPlanCache {

    private final ConcurrentHashMap<UUID, TenantPlan> cache = new ConcurrentHashMap<>();

    /**
     * Returns the cached plan for the given tenant, or {@code TenantPlan.FREE} if unknown.
     */
    public TenantPlan getPlan(UUID tenantId) {
        return cache.getOrDefault(tenantId, TenantPlan.FREE);
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

