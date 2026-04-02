package gr.routify.admin.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.common.client.AmqpServiceClientSupport;
import gr.routify.common.client.KafkaServiceClientSupport;
import gr.routify.common.event.DomainEvent;
import gr.routify.common.event.KafkaTopics;
import gr.routify.common.event.RabbitTopology;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Gateway management client for routify-admin-api.
 *
 * <p>Replaces the previous HTTP actuator polling pattern. All communication
 * with routify-api-gateway is now via message brokers:
 *
 * <ul>
 *   <li><b>Status / loaded config</b>: RabbitMQ request/reply to the
 *       {@code routify.gateway} exchange, routing key {@code gateway.status.request}.
 *       Gateway responds with its live state (route count, config snapshot).</li>
 *   <li><b>Config reload trigger</b>: Kafka {@code routify.gateway.config} topic.
 *       Published by admin-api; all gateway pods consume and reload from DB.
 *       (In practice route-service outbox handles this automatically on save;
 *        this provides a manual trigger.)</li>
 * </ul>
 *
 * <p>HTTP actuator endpoints ({@code /actuator/health}, {@code /actuator/gateway-routes},
 * {@code /actuator/circuitbreakers}) are <strong>no longer called by internal services</strong>.
 * They remain available for external operational tooling only.
 */
@Slf4j
@Component
public class GatewayActuatorClient extends AmqpServiceClientSupport {

    private final KafkaServiceClientSupport kafka;

    public GatewayActuatorClient(RabbitTemplate rabbitTemplate,
                                 ObjectMapper objectMapper,
                                 KafkaTemplate<String, String> kafkaTemplate) {
        super(rabbitTemplate, objectMapper, RabbitTopology.EXCHANGE_GATEWAY, "admin-api");
        this.kafka = new KafkaServiceClientSupport(kafkaTemplate, objectMapper, "admin-api") {};
    }

    // ─── Status / Info ────────────────────────────────────────────────────────

    /**
     * Fetches live gateway status via RabbitMQ request/reply.
     * Returns route count, current config snapshot, and uptime.
     */
    public Map<String, Object> getHealth() {
        return requestGatewayStatus();
    }

    /**
     * Returns the list of currently loaded routes (from status reply).
     */
    public Map<String, Object> getLiveRoutes() {
        Map<String, Object> status = requestGatewayStatus();
        return Map.of(
                "routeCount", status.getOrDefault("routeCount", 0),
                "status",     status.getOrDefault("status", "UNKNOWN")
        );
    }

    /**
     * Returns the config currently loaded by the gateway.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getLoadedConfig() {
        Map<String, Object> status = requestGatewayStatus();
        Object config = status.get("config");
        if (config instanceof Map) {
            return (Map<String, Object>) config;
        }
        return Map.of("error", "config not available");
    }

    /**
     * Circuit breaker states are now surfaced via Kafka metrics topic
     * or Prometheus scraping — not via HTTP actuator.
     */
    public Map<String, Object> getCircuitBreakerStates() {
        return Map.of(
                "note", "Circuit breaker metrics available via Prometheus at /actuator/prometheus",
                "timestamp", Instant.now().toString()
        );
    }

    /**
     * Fetches the live in-memory certificate registry from the gateway via RabbitMQ.
     * Returns a flat map: {@code logicalId → {fingerprint, notAfter, source, status, version}}.
     */
    public Map<String, Object> getCertificates() {
        try {
            return rpc(RabbitTopology.RK_GATEWAY_CERT_REGISTRY, Map.of());
        } catch (Exception e) {
            log.warn("Failed to get cert registry snapshot from gateway: {}", e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }

    /**
     * Gateway metrics are now available via Prometheus.
     */
    public Map<String, Object> getGatewayMetrics() {
        return Map.of(
                "note", "Gateway metrics available via Prometheus at /actuator/prometheus",
                "timestamp", Instant.now().toString()
        );
    }

    // ─── Control ──────────────────────────────────────────────────────────────

    /**
     * Triggers a gateway config reload by publishing a {@code GatewayConfigChanged}
     * Kafka event. All gateway pods consume this and reload from the DB.
     *
     * <p>Note: In normal operation, route-service's outbox handles this automatically
     * after every config save. This is a manual override / on-demand trigger.
     */
    public Map<String, Object> triggerConfigReload() {
        try {
            var event = new DomainEvent.GatewayConfigChanged(
                    UUID.randomUUID(),
                    null,          // tenant-agnostic — all pods reload
                    "manual-reload",
                    "admin-api",
                    Instant.now(),
                    null,
                    null
            );
            kafka.publishEvent(KafkaTopics.GATEWAY_CONFIG_EVENTS, event);
            log.info("Manual gateway reload triggered via Kafka");
            return Map.of("status", "reload_triggered", "timestamp", Instant.now().toString());
        } catch (Exception e) {
            log.error("Failed to trigger gateway reload: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // ─── Private ──────────────────────────────────────────────────────────────

    private Map<String, Object> requestGatewayStatus() {
        try {
            return rpc(RabbitTopology.RK_GATEWAY_STATUS_REQUEST, Map.of());
        } catch (Exception e) {
            log.warn("Failed to get gateway status via RabbitMQ: {}", e.getMessage());
            return Map.of("status", "UNKNOWN", "error", e.getMessage());
        }
    }
}

