package gr.routify.admin.sse;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.common.event.DomainEvent;
import gr.routify.common.event.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Real-time event broadcaster for the dashboard via Server-Sent Events (SSE).
 *
 * <p>All domain events — including certificate and cert-group lifecycle events — are
 * broadcast as typed {@link DomainEvent} instances. No raw-string fallback is used.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashboardEventBroadcaster {

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ObjectMapper objectMapper;

    /**
     * Subscribe to real-time events from the SSE endpoint.
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

    // ─── Typed domain events (routes, filters, gateway, certs, tenants, users) ──

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
            groupId = "routify-admin-sse",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onDomainEvent(DomainEvent event) {
        if (emitters.isEmpty()) return;

        try {
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
                case DomainEvent.CertificateUploaded ignored         -> "certificate.uploaded";
                case DomainEvent.CertificateRevoked ignored          -> "certificate.revoked";
                case DomainEvent.CertificateDeleted ignored          -> "certificate.deleted";
                case DomainEvent.CertificateMappedToGateway ignored  -> "certificate.mapped";
                case DomainEvent.CertificateUnmappedFromGateway ignored -> "certificate.unmapped";
                case DomainEvent.CertGroupCreated ignored            -> "certificate.group.created";
                case DomainEvent.CertGroupUpdated ignored            -> "certificate.group.updated";
                case DomainEvent.CertGroupArchived ignored           -> "certificate.group.archived";
                case DomainEvent.CertGroupDeleted ignored            -> "certificate.group.deleted";
                case DomainEvent.CertAddedToGroup ignored            -> "certificate.group.member.added";
                case DomainEvent.CertRemovedFromGroup ignored        -> "certificate.group.member.removed";
                case DomainEvent.GatewayReloadRequested ignored -> "gateway.reloaded";
                case DomainEvent.GatewayConfigChanged ignored   -> "gateway.config.changed";
                default -> {
                    log.debug("SSE: ignoring unknown event type {}", event.getClass().getSimpleName());
                    yield null;
                }
            };

            if (sseEventType != null) {
                broadcast(sseEventType, objectMapper.writeValueAsString(event));
            }
        } catch (Exception e) {
            log.warn("Failed to process SSE event: {}", e.getMessage());
        }
    }

    // ─── Audit events (raw JSON — no common type yet) ──────────────────────────

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
            String sseType = rawEventType != null ? resolveAuditRawType(rawEventType) : defaultDomain + ".event";
            broadcast(sseType, eventJson);
        } catch (Exception e) {
            log.warn("Failed to process raw SSE event: {}", e.getMessage());
        }
    }

    private String resolveAuditRawType(String rawEventType) {
        return switch (rawEventType) {
            case "REPLAY_COMPLETED"      -> "replay.completed";
            case "REPLAY_BULK_COMPLETED" -> "replay.bulk.completed";
            case "REQUEST_LOGGED"        -> "audit.request.logged";
            default                      -> "audit.event";
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
