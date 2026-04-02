package gr.routify.gateway.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.common.event.DomainEvent;
import gr.routify.common.event.KafkaTopics;
import gr.routify.common.event.RabbitTopology;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loads and applies gateway configuration from the database on startup and on
 * {@code GatewayConfigChanged} Kafka events.
 *
 * <h3>Communication strategy</h3>
 * <ul>
 *   <li><b>Startup / reload config fetch</b>: RabbitMQ request/reply to
 *       routify-route-service ({@code routify.route-service} exchange,
 *       routing key {@code gateway.config.get}). This is a synchronous call
 *       that returns the full persisted config from PostgreSQL.</li>
 *   <li><b>Change notification</b>: Kafka {@code routify.gateway.config} topic.
 *       When admin saves config, route-service publishes {@code GatewayConfigChanged}
 *       via the transactional outbox; all gateway pods consume it and re-fetch
 *       the updated config via RabbitMQ.</li>
 * </ul>
 *
 * <h3>Why both Kafka and RabbitMQ?</h3>
 * <ul>
 *   <li>Kafka is the broadcast/fan-out mechanism — all gateway pods receive the event</li>
 *   <li>RabbitMQ is the data-fetch mechanism — each pod fetches its own config copy</li>
 *   <li>This separation keeps the Kafka event lightweight (no config payload in event)</li>
 *   <li>And allows RabbitMQ load-balancing for the actual data retrieval</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayConfigLoader {

    private final RabbitTemplate                 rabbitTemplate;
    private final ObjectMapper                   objectMapper;
    private final GatewayResilienceConfigApplier resilienceConfigApplier;

    /** The currently applied config — atomically updated on every reload */
    private final AtomicReference<Map<String, Object>> currentConfig =
            new AtomicReference<>(Map.of());

    /**
     * Load gateway config from DB on application startup via RabbitMQ request/reply.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("Gateway starting — loading persisted configuration from route-service via RabbitMQ...");
        loadAndApplyConfig("startup");
    }

    /**
     * Reload gateway config when admin saves changes.
     * All running gateway pods receive this Kafka event and reload simultaneously
     * by calling route-service via RabbitMQ.
     */
    @KafkaListener(
            topics = KafkaTopics.GATEWAY_CONFIG_EVENTS,
            groupId = "routify-gateway-config",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onGatewayConfigChanged(String eventJson) {
        try {
            DomainEvent event = objectMapper.readValue(eventJson, DomainEvent.class);
            if (event instanceof DomainEvent.GatewayConfigChanged changed) {
                log.info("GatewayConfigChanged received: section={} by={}",
                        changed.section(), changed.changedBy());
                loadAndApplyConfig("kafka-event:section=" + changed.section());
            }
        } catch (Exception e) {
            log.warn("Failed to parse GatewayConfigChanged event, reloading anyway: {}", e.getMessage());
            loadAndApplyConfig("kafka-event:parse-error");
        }
    }

    /**
     * Returns the currently loaded config snapshot.
     */
    public Map<String, Object> getConfig() {
        return currentConfig.get();
    }

    /**
     * Returns a specific section of the current config, e.g. "cors", "securityHeaders".
     */
    @SuppressWarnings("unchecked")
    public <T> T getSection(String sectionKey, Class<T> type) {
        Object section = currentConfig.get().get(sectionKey);
        if (section == null) return null;
        return objectMapper.convertValue(section, type);
    }

    // ─── Private ──────────────────────────────────────────────────────────────

    private void loadAndApplyConfig(String trigger) {
        try {
            Object response = rabbitTemplate.convertSendAndReceive(
                    RabbitTopology.EXCHANGE_ROUTE_SERVICE,
                    RabbitTopology.RK_GATEWAY_CONFIG_GET,
                    "{}");

            if (response == null) {
                log.warn("route-service returned null for gateway config (trigger={}). Using last-known config.", trigger);
                return;
            }

            String responseJson = switch (response) {
                case String s    -> s;
                case byte[] b    -> new String(b, java.nio.charset.StandardCharsets.UTF_8);
                default          -> objectMapper.writeValueAsString(response);
            };

            @SuppressWarnings("unchecked")
            Map<String, Object> config = objectMapper.readValue(
                    responseJson, new TypeReference<Map<String, Object>>() {});

            if (config != null && !config.isEmpty()) {
                currentConfig.set(config);
                resilienceConfigApplier.apply(config);
                log.info("Gateway config loaded from route-service via RabbitMQ (trigger={}): {} sections",
                        trigger, config.size());
            } else {
                log.info("route-service returned empty config (trigger={}) — using defaults", trigger);
            }

        } catch (Exception e) {
            log.error("Failed to load gateway config via RabbitMQ (trigger={}): {}. Using last-known config.",
                    trigger, e.getMessage());
        }
    }
}



