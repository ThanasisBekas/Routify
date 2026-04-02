package gr.routify.admin.ws;

import com.fasterxml.jackson.core.type.TypeReference;
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
 *   <li>{@code /topic/events}  — every domain event (route, filter, gateway, config,
 *       certificate, user, tenant, audit)</li>
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

    // ─── Typed domain events → /topic/events ────────────────────────────────────

    @KafkaListener(
            topics = {
                KafkaTopics.ROUTE_EVENTS,
                KafkaTopics.FILTER_EVENTS,
                KafkaTopics.GATEWAY_RELOAD,
                KafkaTopics.GATEWAY_CONFIG_EVENTS,
                KafkaTopics.TENANT_EVENTS,
                KafkaTopics.USER_EVENTS
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
            payload.put("queryKey",   queryKey);
            payload.put("occurredAt", Instant.now().toString());
            payload.put("data",       objectMapper.readValue(eventJson, Map.class));

            messaging.convertAndSend("/topic/events", payload);

            if (isAuditRelevant(event)) {
                messaging.convertAndSend("/topic/audit", payload);
            }

            log.debug("WebSocket broadcast: type={} queryKey={}", type, queryKey);

        } catch (Exception e) {
            log.warn("Failed to broadcast WebSocket event: {}", e.getMessage());
        }
    }

    // ─── Certificate events (plain JSON, not DomainEvent subtypes) ──────────────

    @KafkaListener(
            topics = KafkaTopics.CERT_EVENTS,
            groupId = "routify-admin-ws-cert",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onCertEvent(String eventJson) {
        broadcastRawEvent(eventJson, "certificate");
    }

    @KafkaListener(
            topics = KafkaTopics.CERT_GROUP_EVENTS,
            groupId = "routify-admin-ws-cert-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onCertGroupEvent(String eventJson) {
        broadcastRawEvent(eventJson, "certificate");
    }

    // ─── Audit events (request telemetry, audit trail) ──────────────────────────

    @KafkaListener(
            topics = KafkaTopics.AUDIT_EVENTS,
            groupId = "routify-admin-ws-audit",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onAuditEvent(String eventJson) {
        broadcastRawEvent(eventJson, "audit");
    }

    // ─── Private: broadcast a raw JSON event from services that don't use DomainEvent ─

    private void broadcastRawEvent(String eventJson, String defaultDomain) {
        try {
            Map<String, Object> data = objectMapper.readValue(eventJson, new TypeReference<>() {});

            String rawEventType = str(data.get("eventType"));
            String type     = resolveRawType(rawEventType, defaultDomain);
            String queryKey = resolveRawQueryKey(rawEventType, defaultDomain);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type",       type);
            payload.put("queryKey",   queryKey);
            payload.put("occurredAt", Instant.now().toString());
            payload.put("data",       data);

            messaging.convertAndSend("/topic/events", payload);

            // Certificate & audit events are also audit-relevant
            messaging.convertAndSend("/topic/audit", payload);

            log.debug("WebSocket broadcast (raw): type={} queryKey={}", type, queryKey);

        } catch (Exception e) {
            log.warn("Failed to broadcast raw WebSocket event: {}", e.getMessage());
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
            case DomainEvent.TenantCreated ignored       -> "tenant.created";
            case DomainEvent.TenantUpdated ignored       -> "tenant.updated";
            case DomainEvent.TenantSuspended ignored     -> "tenant.suspended";
            case DomainEvent.UserCreated ignored         -> "user.created";
            case DomainEvent.UserUpdated ignored         -> "user.updated";
            case DomainEvent.UserDeleted ignored         -> "user.deleted";
            case DomainEvent.CertRotated ignored         -> "certificate.rotated";
            case DomainEvent.GatewayReloadRequested ignored -> "gateway.reloaded";
            case DomainEvent.GatewayConfigChanged ignored   -> "gateway.config.changed";
        };
    }

    /**
     * Maps each event type to the React Query cache key(s) that should be invalidated.
     * The dashboard uses this to surgically re-fetch only affected data.
     */
    private String resolveQueryKey(DomainEvent event) {
        return switch (event) {
            case DomainEvent.RouteCreated ignored      -> "routes";
            case DomainEvent.RouteCloned ignored       -> "routes";
            case DomainEvent.RouteUpdated ignored      -> "routes";
            case DomainEvent.RouteActivated ignored    -> "routes";
            case DomainEvent.RouteDeactivated ignored  -> "routes";
            case DomainEvent.RouteDeleted ignored      -> "routes";
            case DomainEvent.FilterCreated ignored     -> "filters";
            case DomainEvent.FilterUpdated ignored     -> "filters";
            case DomainEvent.FilterDeleted ignored     -> "filters";
            case DomainEvent.FilterAttached ignored    -> "routes";
            case DomainEvent.FilterDetached ignored    -> "routes";
            case DomainEvent.TenantCreated ignored     -> "tenants";
            case DomainEvent.TenantUpdated ignored     -> "tenants";
            case DomainEvent.TenantSuspended ignored   -> "tenants";
            case DomainEvent.UserCreated ignored       -> "users";
            case DomainEvent.UserUpdated ignored       -> "users";
            case DomainEvent.UserDeleted ignored       -> "users";
            case DomainEvent.CertRotated ignored       -> "certificates";
            case DomainEvent.GatewayReloadRequested ignored -> "gateway-status";
            case DomainEvent.GatewayConfigChanged ignored   -> "gateway-config";
        };
    }

    /**
     * Resolves a WebSocket event type from a raw (non-DomainEvent) payload's eventType field.
     * Used for certificate vault events and audit events which publish plain JSON.
     */
    private String resolveRawType(String rawEventType, String defaultDomain) {
        if (rawEventType == null) return defaultDomain + ".event";
        return switch (rawEventType) {
            // ── Certificate events ──
            case "CERTIFICATE_UPLOADED"                -> "certificate.uploaded";
            case "CERTIFICATE_REVOKED"                 -> "certificate.revoked";
            case "CERTIFICATE_DELETED"                 -> "certificate.deleted";
            case "CERTIFICATE_MAPPED_TO_GATEWAY"       -> "certificate.mapped";
            case "CERTIFICATE_UNMAPPED_FROM_GATEWAY"   -> "certificate.unmapped";
            // ── Certificate group events ──
            case "CERT_GROUP_CREATED"                  -> "certificate.group.created";
            case "CERT_GROUP_UPDATED"                  -> "certificate.group.updated";
            case "CERT_GROUP_ARCHIVED"                 -> "certificate.group.archived";
            case "CERT_GROUP_DELETED"                  -> "certificate.group.deleted";
            case "CERT_ADDED_TO_GROUP"                 -> "certificate.group.member.added";
            case "CERT_REMOVED_FROM_GROUP"             -> "certificate.group.member.removed";
            // ── Audit / replay events ──
            case "REPLAY_COMPLETED"                    -> "replay.completed";
            case "REPLAY_BULK_COMPLETED"               -> "replay.bulk.completed";
            case "REQUEST_LOGGED"                      -> "audit.request.logged";
            default                                    -> defaultDomain + ".event";
        };
    }

    /**
     * Maps raw event types to the React Query cache key that should be invalidated.
     */
    private String resolveRawQueryKey(String rawEventType, String defaultDomain) {
        if (rawEventType == null) return defaultDomain;
        return switch (rawEventType) {
            case "CERTIFICATE_UPLOADED", "CERTIFICATE_REVOKED", "CERTIFICATE_DELETED",
                 "CERTIFICATE_MAPPED_TO_GATEWAY", "CERTIFICATE_UNMAPPED_FROM_GATEWAY"
                    -> "certificates";
            case "CERT_GROUP_CREATED", "CERT_GROUP_UPDATED", "CERT_GROUP_ARCHIVED",
                 "CERT_GROUP_DELETED", "CERT_ADDED_TO_GROUP", "CERT_REMOVED_FROM_GROUP"
                    -> "cert-groups";
            case "REPLAY_COMPLETED", "REPLAY_BULK_COMPLETED"
                    -> "replay";
            case "REQUEST_LOGGED"
                    -> "audit";
            default -> defaultDomain;
        };
    }

    private boolean isAuditRelevant(DomainEvent event) {
        return !(event instanceof DomainEvent.GatewayConfigChanged)
            && !(event instanceof DomainEvent.GatewayReloadRequested);
    }

    private static String str(Object val) {
        return val != null ? val.toString() : null;
    }
}

