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
                KafkaTopics.USER_EVENTS,
                KafkaTopics.CERT_EVENTS,
                KafkaTopics.CERT_GROUP_EVENTS
            },
            groupId = "routify-admin-ws",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onDomainEvent(DomainEvent event) {
        try {
            String type = resolveType(event);
            String queryKey = resolveQueryKey(event);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type",       type);
            payload.put("queryKey",   queryKey);
            payload.put("occurredAt", Instant.now().toString());
            payload.put("data",       objectMapper.convertValue(event, Map.class));

            messaging.convertAndSend("/topic/events", payload);

            if (isAuditRelevant(event)) {
                messaging.convertAndSend("/topic/audit", payload);
            }

            log.debug("WebSocket broadcast: type={} queryKey={}", type, queryKey);

        } catch (Exception e) {
            log.warn("Failed to broadcast WebSocket event: {}", e.getMessage());
        }
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

    // ─── Private: broadcast a raw JSON audit event ──────────────────────────────

    private void broadcastRawEvent(String eventJson, String defaultDomain) {
        try {
            Map<String, Object> data = objectMapper.readValue(eventJson, new TypeReference<>() {});

            String rawEventType = str(data.get("eventType"));
            String type     = resolveAuditRawType(rawEventType, defaultDomain);
            String queryKey = resolveAuditRawQueryKey(rawEventType, defaultDomain);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type",       type);
            payload.put("queryKey",   queryKey);
            payload.put("occurredAt", Instant.now().toString());
            payload.put("data",       data);

            messaging.convertAndSend("/topic/events", payload);
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
            case DomainEvent.CertRotated ignored                    -> "certificate.rotated";
            case DomainEvent.CertificateUploaded ignored            -> "certificate.uploaded";
            case DomainEvent.CertificateRevoked ignored             -> "certificate.revoked";
            case DomainEvent.CertificateDeleted ignored             -> "certificate.deleted";
            case DomainEvent.CertificateMappedToGateway ignored     -> "certificate.mapped";
            case DomainEvent.CertificateUnmappedFromGateway ignored -> "certificate.unmapped";
            case DomainEvent.CertGroupCreated ignored               -> "certificate.group.created";
            case DomainEvent.CertGroupUpdated ignored               -> "certificate.group.updated";
            case DomainEvent.CertGroupArchived ignored              -> "certificate.group.archived";
            case DomainEvent.CertGroupDeleted ignored               -> "certificate.group.deleted";
            case DomainEvent.CertAddedToGroup ignored               -> "certificate.group.member.added";
            case DomainEvent.CertRemovedFromGroup ignored           -> "certificate.group.member.removed";
            case DomainEvent.GatewayReloadRequested ignored -> "gateway.reloaded";
            case DomainEvent.GatewayConfigChanged ignored   -> "gateway.config.changed";
            default -> "unknown.event";
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
            case DomainEvent.CertRotated ignored                    -> "certificates";
            case DomainEvent.CertificateUploaded ignored            -> "certificates";
            case DomainEvent.CertificateRevoked ignored             -> "certificates";
            case DomainEvent.CertificateDeleted ignored             -> "certificates";
            case DomainEvent.CertificateMappedToGateway ignored     -> "certificates";
            case DomainEvent.CertificateUnmappedFromGateway ignored -> "certificates";
            case DomainEvent.CertGroupCreated ignored               -> "cert-groups";
            case DomainEvent.CertGroupUpdated ignored               -> "cert-groups";
            case DomainEvent.CertGroupArchived ignored              -> "cert-groups";
            case DomainEvent.CertGroupDeleted ignored               -> "cert-groups";
            case DomainEvent.CertAddedToGroup ignored               -> "cert-groups";
            case DomainEvent.CertRemovedFromGroup ignored           -> "cert-groups";
            case DomainEvent.GatewayReloadRequested ignored -> "gateway-status";
            case DomainEvent.GatewayConfigChanged ignored   -> "gateway-config";
            default -> "unknown";
        };
    }

    /**
     * Resolves a WebSocket event type from a raw audit payload's eventType field.
     */
    private String resolveAuditRawType(String rawEventType, String defaultDomain) {
        if (rawEventType == null) return defaultDomain + ".event";
        return switch (rawEventType) {
            case "REPLAY_COMPLETED"      -> "replay.completed";
            case "REPLAY_BULK_COMPLETED" -> "replay.bulk.completed";
            case "REQUEST_LOGGED"        -> "audit.request.logged";
            default                      -> defaultDomain + ".event";
        };
    }

    /**
     * Maps raw audit event types to the React Query cache key that should be invalidated.
     */
    private String resolveAuditRawQueryKey(String rawEventType, String defaultDomain) {
        if (rawEventType == null) return defaultDomain;
        return switch (rawEventType) {
            case "REPLAY_COMPLETED", "REPLAY_BULK_COMPLETED" -> "replay";
            case "REQUEST_LOGGED"                            -> "audit";
            default                                          -> defaultDomain;
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

