package io.routify.gateway.routing;

import io.routify.common.event.DomainEvent;
import io.routify.common.event.KafkaTopics;
import io.routify.common.observability.RoutifyMetrics;
import io.routify.gateway.cluster.GatewayInstanceRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Listens for route change events and triggers zero-downtime hot-reload.
 *
 * <p>This component bridges the Kafka messaging layer to the
 * {@link DynamicRouteDefinitionLocator}. When a {@code GatewayReloadRequested}
 * event is received, it triggers an immediate reload of all route definitions
 * without any gateway restart.
 *
 * <p>After each successful reload, the {@link GatewayInstanceRegistry} is
 * notified so that the local and global config versions are incremented
 * and heartbeat data stays up-to-date.
 *
 * <h3>Startup Sequence:</h3>
 * <ol>
 *   <li>Gateway starts, WebFlux context initializes</li>
 *   <li>{@link ApplicationReadyEvent} fires → initial route load</li>
 *   <li>Routes are loaded from Redis cache or routify-route-service</li>
 *   <li>Gateway is ready to serve traffic</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DynamicRouteRefreshListener {

    private final DynamicRouteDefinitionLocator routeLocator;
    private final GatewayInstanceRegistry instanceRegistry;
    private final RoutifyMetrics metrics;

    /**
     * Pre-warm route table on application startup.
     * Ensures the gateway can serve traffic immediately without waiting for
     * the first Kafka event.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("Application ready — loading initial route definitions");
        routeLocator.refresh();
        int routeCount = routeLocator.getLoadedRouteCount();
        log.info("Initial route load complete: {} routes active", routeCount);
        instanceRegistry.incrementConfigVersion(routeCount);
        metrics.setGatewayConfigVersion(instanceRegistry.getConfigVersion().get());
    }

    /**
     * Reacts to {@code GatewayReloadRequested} events from routify-route-service.
     * This is the zero-downtime hot-reload trigger.
     *
     * <p>Event sources:
     * <ul>
     *   <li>Route activated/deactivated</li>
     *   <li>Route updated while ACTIVE</li>
     *   <li>Filter attached/detached from ACTIVE route</li>
     *   <li>Filter configuration updated while in use</li>
     *   <li>Certificate rotation</li>
     * </ul>
     */
    @KafkaListener(
            topics = KafkaTopics.GATEWAY_RELOAD,
            groupId = "routify-gateway-reload",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onGatewayReloadRequested(DomainEvent event) {
        try {
            if (event instanceof DomainEvent.GatewayReloadRequested reload) {
                log.info("Gateway reload requested: reason='{}' tenant={}",
                        reload.reason(), reload.tenantId());
                routeLocator.forceRefresh();
                updateRegistryAfterReload();
            }
        } catch (Exception e) {
            log.error("Failed to process gateway reload event: {}", e.getMessage(), e);
            // Force refresh anyway on parse error to stay consistent
            routeLocator.forceRefresh();
            updateRegistryAfterReload();
        }
    }

    /**
     * Also listen to ROUTE_EVENTS for redundancy.
     * If a RouteActivated/Deactivated event arrives and the GATEWAY_RELOAD
     * topic consumer is lagging, we still pick it up here.
     */
    @KafkaListener(
            topics = KafkaTopics.ROUTE_EVENTS,
            groupId = "routify-gateway-route-events",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onRouteEvent(DomainEvent event) {
        try {
            boolean requiresReload = switch (event) {
                case DomainEvent.RouteActivated ignored   -> true;
                case DomainEvent.RouteDeactivated ignored -> true;
                case DomainEvent.RouteDeleted ignored     -> true;
                default                                   -> false;
            };

            if (requiresReload) {
                log.debug("Route lifecycle event — triggering gateway reload: {}",
                        event.getClass().getSimpleName());
                routeLocator.refresh();
                updateRegistryAfterReload();
            }
        } catch (Exception e) {
            log.warn("Failed to process route event: {}", e.getMessage());
        }
    }

    /**
     * Updates the instance registry with the latest route count and config version
     * after a successful route reload.
     */
    private void updateRegistryAfterReload() {
        int routeCount = routeLocator.getLoadedRouteCount();
        instanceRegistry.incrementConfigVersion(routeCount);
        metrics.setGatewayConfigVersion(instanceRegistry.getConfigVersion().get());
    }
}
