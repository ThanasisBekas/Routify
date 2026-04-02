package gr.routify.admin.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.common.event.DomainEvent;
import gr.routify.common.event.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bridges Kafka domain events → WebSocket STOMP topics for the admin dashboard.
 *
 * <p>All subscribed dashboard clients receive push messages with zero polling.
 * Topics:
 * <ul>
 *   <li>{@code /topic/events}  — every domain event (route, filter, gateway, config)</li>
 *   <li>{@code /topic/audit}   — domain events formatted as audit entries for the live feed</li>
 *   <li>{@code /topic/metrics} — periodic circuit breaker / health snapshots</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketEventBroadcaster {

    private final SimpMessagingTemplate messaging;
    private final ObjectMapper          objectMapper;

    // ─── Domain events → /topic/events ────────────────────────────────────────

    @KafkaListener(
            topics = {
                KafkaTopics.ROUTE_EVENTS,
                KafkaTopics.FILTER_EVENTS,
                KafkaTopics.GATEWAY_RELOAD,
                KafkaTopics.GATEWAY_CONFIG_EVENTS
            },
            groupId = "routify-admin-ws",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onDomainEvent(String eventJson) {
        try {
            DomainEvent event = objectMapper.readValue(eventJson, DomainEvent.class);

            String type = resolveType(event);
            String queryKey = resolveQueryKey(event);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type",       type);
            payload.put("queryKey",   queryKey);   // which React Query cache key to invalidate
            payload.put("occurredAt", Instant.now().toString());
            payload.put("data",       objectMapper.readValue(eventJson, Map.class));

            // Broadcast to all dashboard subscribers
            messaging.convertAndSend("/topic/events", payload);

            // Also push to the audit live feed for audit events
            if (isAuditRelevant(event)) {
                messaging.convertAndSend("/topic/audit", payload);
            }

            log.debug("WebSocket broadcast: type={} queryKey={}", type, queryKey);

        } catch (Exception e) {
            log.warn("Failed to broadcast WebSocket event: {}", e.getMessage());
        }
    }

    // ─── Typed event resolution ────────────────────────────────────────────────

    private String resolveType(DomainEvent event) {
        return switch (event) {
            case DomainEvent.RouteCreated ignored        -> "route.created";
            case DomainEvent.RouteCloned ignored         -> "route.cloned";
            case DomainEvent.RouteUpdated ignored        -> "route.updated";
            case DomainEvent.RouteActivated ignored      -> "route.activated";
            case DomainEvent.RouteDeactivated ignored    -> "route.deactivated";
            case DomainEvent.RouteDeleted ignored        -> "route.deleted";
            case DomainEvent.FilterCreated ignored       -> "filter.created";
            case DomainEvent.FilterUpdated ignored       -> "filter.updated";
            case DomainEvent.FilterDeleted ignored       -> "filter.deleted";
            case DomainEvent.FilterAttached ignored      -> "filter.attached";
            case DomainEvent.FilterDetached ignored      -> "filter.detached";
            case DomainEvent.GatewayReloadRequested ignored -> "gateway.reloaded";
            case DomainEvent.GatewayConfigChanged ignored   -> "gateway.config.changed";
            default -> "event";
        };
    }

    /**
     * Maps each event type to the React Query cache key(s) that should be invalidated.
     * The dashboard uses this to surgically re-fetch only affected data.
     */
    private String resolveQueryKey(DomainEvent event) {
        return switch (event) {
            case DomainEvent.RouteCreated ignored      -> "routes";
            case DomainEvent.RouteCloned ignored1b     -> "routes";
            case DomainEvent.RouteUpdated ignored2     -> "routes";
            case DomainEvent.RouteActivated ignored3   -> "routes";
            case DomainEvent.RouteDeactivated ignored4 -> "routes";
            case DomainEvent.RouteDeleted ignored5     -> "routes";
            case DomainEvent.FilterCreated ignored6    -> "filters";
            case DomainEvent.FilterUpdated ignored7    -> "filters";
            case DomainEvent.FilterDeleted ignored8    -> "filters";
            case DomainEvent.FilterAttached ignored9   -> "routes";
            case DomainEvent.FilterDetached ignored10  -> "routes";
            case DomainEvent.GatewayReloadRequested ignored11 -> "gateway-status";
            case DomainEvent.GatewayConfigChanged ignored12   -> "gateway-config";
            default -> "misc";
        };
    }

    private boolean isAuditRelevant(DomainEvent event) {
        return !(event instanceof DomainEvent.GatewayConfigChanged)
            && !(event instanceof DomainEvent.GatewayReloadRequested);
    }
}

