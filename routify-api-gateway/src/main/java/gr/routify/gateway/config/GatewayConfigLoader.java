package gr.routify.gateway.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.common.event.DomainEvent;
import gr.routify.common.event.KafkaTopics;
import gr.routify.common.event.RabbitTopology;
import gr.routify.gateway.routing.DynamicRouteDefinitionLocator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
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
 *
 * <h3>Route rebuild on config change</h3>
 * <p>Certain config sections (e.g. {@code tenantIsolation}, {@code authProviders})
 * affect route definitions — predicates and filters are baked in at build time by
 * {@link gr.routify.gateway.routing.RouteDefinitionBuilder}. When such a section
 * changes, we trigger a full route rebuild via {@link DynamicRouteDefinitionLocator#forceRefresh()}
 * so that predicates (e.g. {@code Header=X-Tenant-Id}) are added or removed to
 * reflect the new config. The {@code @Lazy} injection of the locator breaks the
 * circular dependency chain:
 * {@code GatewayConfigLoader → DynamicRouteDefinitionLocator → RouteDefinitionBuilder → GatewayConfigLoader}.
 */
@Slf4j
@Component
public class GatewayConfigLoader {

    /**
     * Config sections whose changes affect route definitions (predicates/filters)
     * and therefore require a full route rebuild, not just a config map update.
     */
    private static final Set<String> ROUTE_AFFECTING_SECTIONS = Set.of(
            "TENANT_ISOLATION",
            "AUTH_PROVIDERS",
            "GLOBAL_FILTER_ENTRIES"
    );

    private final RabbitTemplate                 rabbitTemplate;
    private final ObjectMapper                   objectMapper;
    private final GatewayResilienceConfigApplier resilienceConfigApplier;
    private final DynamicRouteDefinitionLocator  routeLocator;

    /** The currently applied config — atomically updated on every reload */
    private final AtomicReference<Map<String, Object>> currentConfig =
            new AtomicReference<>(Map.of());

    /**
     * Guards against triggering a route rebuild during initial startup.
     * {@link gr.routify.gateway.routing.DynamicRouteRefreshListener} already
     * handles the initial route load — we only rebuild on subsequent Kafka events.
     */
    private final AtomicBoolean initialLoadDone = new AtomicBoolean(false);

    public GatewayConfigLoader(
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            GatewayResilienceConfigApplier resilienceConfigApplier,
            @Lazy DynamicRouteDefinitionLocator routeLocator) {
        this.rabbitTemplate         = rabbitTemplate;
        this.objectMapper           = objectMapper;
        this.resilienceConfigApplier = resilienceConfigApplier;
        this.routeLocator           = routeLocator;
    }

    /**
     * Load gateway config from DB on application startup via RabbitMQ request/reply.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("Gateway starting — loading persisted configuration from route-service via RabbitMQ...");
        loadAndApplyConfig("startup", false);
        initialLoadDone.set(true);
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
    public void onGatewayConfigChanged(DomainEvent event) {
        try {
            if (event instanceof DomainEvent.GatewayConfigChanged changed) {
                log.info("GatewayConfigChanged received: section={} by={}",
                        changed.section(), changed.changedBy());
                boolean needsRouteRebuild = ROUTE_AFFECTING_SECTIONS.contains(changed.section());
                loadAndApplyConfig("kafka-event:section=" + changed.section(), needsRouteRebuild);
            }
        } catch (Exception e) {
            log.warn("Failed to process GatewayConfigChanged event, reloading anyway: {}", e.getMessage());
            // On parse error we cannot determine the section — rebuild routes to be safe
            loadAndApplyConfig("kafka-event:parse-error", true);
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

    private void loadAndApplyConfig(String trigger, boolean rebuildRoutes) {
        try {
            Object response = rabbitTemplate.convertSendAndReceive(
                    RabbitTopology.EXCHANGE_ROUTE_SERVICE,
                    RabbitTopology.RK_GATEWAY_CONFIG_GET,
                    new gr.routify.common.event.QueryRequest.GatewayConfigGet());

            if (response == null) {
                log.warn("route-service returned null for gateway config (trigger={}). Using last-known config.", trigger);
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> config = switch (response) {
                case gr.routify.common.event.QueryResponse.GatewayConfig gc -> gc.config();
                case Map<?, ?> m -> (Map<String, Object>) m;
                case String s    -> objectMapper.readValue(s, new TypeReference<Map<String, Object>>() {});
                case byte[] b    -> objectMapper.readValue(new String(b, java.nio.charset.StandardCharsets.UTF_8),
                                            new TypeReference<Map<String, Object>>() {});
                default          -> objectMapper.convertValue(response, new TypeReference<>() {});
            };

            if (config != null && !config.isEmpty()) {
                currentConfig.set(config);
                resilienceConfigApplier.apply(config);
                log.info("Gateway config loaded from route-service via RabbitMQ (trigger={}): {} sections",
                        trigger, config.size());

                // Rebuild route definitions when a route-affecting section changed.
                // Skip during initial startup — DynamicRouteRefreshListener handles that.
                if (rebuildRoutes && initialLoadDone.get()) {
                    log.info("Route-affecting config section changed (trigger={}) — " +
                             "triggering route definition rebuild so predicates reflect new config", trigger);
                    routeLocator.forceRefresh();
                }
            } else {
                log.info("route-service returned empty config (trigger={}) — using defaults", trigger);
            }

        } catch (Exception e) {
            log.error("Failed to load gateway config via RabbitMQ (trigger={}): {}. Using last-known config.",
                    trigger, e.getMessage());
        }
    }
}



