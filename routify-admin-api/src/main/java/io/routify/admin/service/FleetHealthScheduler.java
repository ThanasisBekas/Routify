package io.routify.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.routify.admin.dto.FleetStatusResponse;
import io.routify.admin.dto.FleetStatusResponse.InstanceStatus;
import io.routify.common.event.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Periodically checks gateway fleet health and publishes
 * {@code GATEWAY_CONFIG_DRIFT} audit events when any instance's config version
 * is behind the global version for &gt; 60s, or when an instance disappears
 * from the registry (heartbeat expired).
 *
 * <p>Runs every 60 seconds in admin-api. The existing
 * {@code WebhookEventConsumer} in identity-service subscribes to
 * {@code AUDIT_EVENTS} and dispatches matching webhook notifications.
 *
 * <p>Deduplication: tracks which instance IDs have already been reported as stale.
 * A drift webhook is only fired once per stale transition. When the instance
 * recovers (catches up or re-registers), it is removed from the tracked set
 * so that a future drift event is published again if it falls behind.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FleetHealthScheduler {

    private final DashboardStatsService statsService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Set of instance IDs that have already been reported as stale/unresponsive.
     * Prevents duplicate webhook notifications on every 60 s check.
     */
    private final Set<String> reportedStaleInstances = ConcurrentHashMap.newKeySet();

    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void checkFleetHealth() {
        try {
            FleetStatusResponse fleet = statsService.getFleetStatus();
            if (fleet.instanceCount() == 0) {
                reportedStaleInstances.clear();
                return;
            }

            for (InstanceStatus instance : fleet.instances()) {
                if ("STALE".equals(instance.status())) {
                    if (reportedStaleInstances.add(instance.instanceId())) {
                        log.warn("Config drift detected: instance={} localVersion={} globalVersion={}",
                                instance.instanceId(), instance.configVersion(), fleet.globalConfigVersion());
                        publishDriftEvent(instance, fleet.globalConfigVersion());
                    }
                } else {
                    // Instance recovered — remove from reported set
                    reportedStaleInstances.remove(instance.instanceId());
                }
            }

            // Clean up any reported IDs that are no longer in the fleet
            Set<String> currentIds = new HashSet<>();
            fleet.instances().forEach(i -> currentIds.add(i.instanceId()));
            reportedStaleInstances.removeIf(id -> !currentIds.contains(id));

        } catch (Exception e) {
            log.warn("Fleet health check failed: {}", e.getMessage());
        }
    }

    private void publishDriftEvent(InstanceStatus instance, long globalConfigVersion) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("eventType", "GATEWAY_CONFIG_DRIFT");
            event.put("eventId", UUID.randomUUID().toString());
            event.put("tenantId", null);
            event.put("aggregateType", "gateway");
            event.put("aggregateId", instance.instanceId());
            event.put("actorId", "system");
            event.put("occurredAt", Instant.now().toString());
            event.put("instanceId", instance.instanceId());
            event.put("hostname", instance.hostname());
            event.put("instanceConfigVersion", instance.configVersion());
            event.put("globalConfigVersion", globalConfigVersion);
            event.put("routeCount", instance.routeCount());

            String json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(KafkaTopics.AUDIT_EVENTS, "system", json);
            log.info("Published GATEWAY_CONFIG_DRIFT audit event for instance={}", instance.instanceId());
        } catch (Exception e) {
            log.error("Failed to publish GATEWAY_CONFIG_DRIFT event: {}", e.getMessage(), e);
        }
    }
}
