package io.routify.route.messaging;

import io.routify.common.domain.TenantPlan;
import io.routify.common.event.DomainEvent;
import io.routify.common.event.KafkaTopics;
import io.routify.route.service.TenantPlanCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer that listens to {@code TENANT_EVENTS} to maintain the local
 * {@link TenantPlanCache} used for quota enforcement.
 *
 * <p>Only tenant lifecycle events with plan information are relevant.
 * All other event types are silently skipped.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantEventKafkaConsumer {

    private final TenantPlanCache tenantPlanCache;

    @KafkaListener(
            topics = KafkaTopics.TENANT_EVENTS,
            groupId = "routify-route-service-tenant-events",
            containerFactory = "routeCommandKafkaListenerContainerFactory"
    )
    public void onTenantEvent(DomainEvent event, Acknowledgment ack) {
        try {
            switch (event) {
                case DomainEvent.TenantCreated tc -> {
                    TenantPlan plan = parsePlan(tc.plan());
                    if (plan != null) {
                        tenantPlanCache.put(tc.tenantId(), plan);
                        log.info("TenantPlanCache: tenant created tenantId={} plan={}", tc.tenantId(), plan);
                    }
                }
                case DomainEvent.TenantUpdated tu -> {
                    TenantPlan plan = parsePlan(tu.plan());
                    if (plan != null) {
                        tenantPlanCache.put(tu.tenantId(), plan);
                        log.info("TenantPlanCache: tenant updated tenantId={} plan={}", tu.tenantId(), plan);
                    }
                }
                case DomainEvent.TenantSuspended ts -> {
                    tenantPlanCache.remove(ts.tenantId());
                    log.info("TenantPlanCache: tenant suspended tenantId={}", ts.tenantId());
                }
                default -> log.debug("TenantEventKafkaConsumer: ignoring event type={}", event.getClass().getSimpleName());
            }
        } catch (Exception e) {
            log.error("Failed to process tenant event: type={} — {}", event.getClass().getSimpleName(), e.getMessage(), e);
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

