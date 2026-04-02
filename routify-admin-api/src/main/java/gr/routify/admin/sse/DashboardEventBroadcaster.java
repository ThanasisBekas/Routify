package gr.routify.admin.sse;

import com.fasterxml.jackson.core.type.TypeReference;
import gr.routify.common.event.DomainEvent;
import gr.routify.common.event.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Real-time event broadcaster for the dashboard via Server-Sent Events (SSE).
 *
 * <p>The dashboard opens a persistent SSE connection to receive live updates when:
 * <ul>
 *   <li>A route is created/activated/deactivated/deleted</li>
 *   <li>A filter is created/updated/deleted</li>
 *   <li>Gateway reload is requested</li>
 *   <li>A tenant is created/updated/suspended</li>
 *   <li>A user is created/updated/deleted</li>
 *   <li>A certificate is uploaded/revoked/deleted/mapped</li>
 *   <li>A certificate group is created/updated/archived/deleted</li>
 *   <li>An audit or replay event occurs</li>
 * </ul>
 *
 * <p>This eliminates the need for dashboard polling — the UI updates instantly
 * when any changes are made by any user on any session.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashboardEventBroadcaster {

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ObjectMapper objectMapper;

    /**
     * Subscribe to real-time route events from the SSE endpoint.
     * Returns an SSE emitter that will receive all future events.
     */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L); // No timeout — long-lived connection
        emitters.add(emitter);

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(ex -> emitters.remove(emitter));

        // Send initial "connected" event
        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data("{\"message\":\"Connected to Routify dashboard events\"}"));
        } catch (IOException e) {
            emitters.remove(emitter);
        }

        log.debug("SSE client connected. Total subscribers: {}", emitters.size());
        return emitter;
    }

    @KafkaListener(
            topics = {KafkaTopics.ROUTE_EVENTS, KafkaTopics.FILTER_EVENTS,
                      KafkaTopics.GATEWAY_RELOAD, KafkaTopics.GATEWAY_CONFIG_EVENTS,
                      KafkaTopics.TENANT_EVENTS, KafkaTopics.USER_EVENTS},
            groupId = "routify-admin-sse",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onDomainEvent(String eventJson) {
        if (emitters.isEmpty()) return;

        try {
            DomainEvent event = objectMapper.readValue(eventJson, DomainEvent.class);

            String sseEventType = switch (event) {
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

            broadcast(sseEventType, eventJson);
        } catch (Exception e) {
            log.warn("Failed to process SSE event: {}", e.getMessage());
        }
    }

    // ─── Certificate events (plain JSON, not DomainEvent subtypes) ──────────────

    @KafkaListener(
            topics = KafkaTopics.CERT_EVENTS,
            groupId = "routify-admin-sse-cert",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onCertEvent(String eventJson) {
        broadcastRawEvent(eventJson, "certificate");
    }

    @KafkaListener(
            topics = KafkaTopics.CERT_GROUP_EVENTS,
            groupId = "routify-admin-sse-cert-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onCertGroupEvent(String eventJson) {
        broadcastRawEvent(eventJson, "certificate");
    }

    // ─── Audit events ───────────────────────────────────────────────────────────

    @KafkaListener(
            topics = KafkaTopics.AUDIT_EVENTS,
            groupId = "routify-admin-sse-audit",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onAuditEvent(String eventJson) {
        broadcastRawEvent(eventJson, "audit");
    }

    // ─── Private helpers ───────────────────────────────────────────────────────

    private void broadcastRawEvent(String eventJson, String defaultDomain) {
        if (emitters.isEmpty()) return;
        try {
            Map<String, Object> data = objectMapper.readValue(eventJson, new TypeReference<>() {});
            String rawEventType = data.get("eventType") != null ? data.get("eventType").toString() : null;
            String sseType = resolveRawType(rawEventType, defaultDomain);
            broadcast(sseType, eventJson);
        } catch (Exception e) {
            log.warn("Failed to process raw SSE event: {}", e.getMessage());
        }
    }

    private String resolveRawType(String rawEventType, String defaultDomain) {
        if (rawEventType == null) return defaultDomain + ".event";
        return switch (rawEventType) {
            case "CERTIFICATE_UPLOADED"                -> "certificate.uploaded";
            case "CERTIFICATE_REVOKED"                 -> "certificate.revoked";
            case "CERTIFICATE_DELETED"                 -> "certificate.deleted";
            case "CERTIFICATE_MAPPED_TO_GATEWAY"       -> "certificate.mapped";
            case "CERTIFICATE_UNMAPPED_FROM_GATEWAY"   -> "certificate.unmapped";
            case "CERT_GROUP_CREATED"                  -> "certificate.group.created";
            case "CERT_GROUP_UPDATED"                  -> "certificate.group.updated";
            case "CERT_GROUP_ARCHIVED"                 -> "certificate.group.archived";
            case "CERT_GROUP_DELETED"                  -> "certificate.group.deleted";
            case "CERT_ADDED_TO_GROUP"                 -> "certificate.group.member.added";
            case "CERT_REMOVED_FROM_GROUP"             -> "certificate.group.member.removed";
            case "REPLAY_COMPLETED"                    -> "replay.completed";
            case "REPLAY_BULK_COMPLETED"               -> "replay.bulk.completed";
            case "REQUEST_LOGGED"                      -> "audit.request.logged";
            default                                    -> defaultDomain + ".event";
        };
    }

    private void broadcast(String eventName, String data) {
        List<SseEmitter> deadEmitters = new CopyOnWriteArrayList<>();

        emitters.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(data));
            } catch (IOException e) {
                deadEmitters.add(emitter);
            }
        });

        emitters.removeAll(deadEmitters);
        if (!deadEmitters.isEmpty()) {
            log.debug("Removed {} dead SSE emitters", deadEmitters.size());
        }
    }

    public int getSubscriberCount() { return emitters.size(); }
}

