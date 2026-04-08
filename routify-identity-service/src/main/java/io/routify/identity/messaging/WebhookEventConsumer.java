package io.routify.identity.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.routify.common.domain.WebhookEventType;
import io.routify.common.event.DomainEvent;
import io.routify.common.event.KafkaTopics;
import io.routify.identity.domain.WebhookSubscription;
import io.routify.identity.service.WebhookDispatcher;
import io.routify.identity.service.WebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Kafka consumer that subscribes to domain event topics and dispatches matching
 * webhook notifications to registered subscriptions.
 *
 * <p>For each incoming event:
 * <ol>
 *   <li>Map the Kafka domain event to a {@link WebhookEventType}.</li>
 *   <li>Extract the {@code tenantId} from the event payload.</li>
 *   <li>Query for ACTIVE subscriptions matching the tenant + event type.</li>
 *   <li>Dispatch each matching subscription via {@link WebhookDispatcher}.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookEventConsumer {

    private final WebhookService webhookService;
    private final WebhookDispatcher webhookDispatcher;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = KafkaTopics.ROUTE_EVENTS,
            groupId = "routify-identity-service-webhook-route-events",
            containerFactory = "webhookKafkaListenerContainerFactory"
    )
    public void onRouteEvent(DomainEvent event) {
        String eventType = mapRouteEvent(event);
        if (eventType != null) {
            dispatchForEvent(event.tenantId(), eventType, event);
        }
    }

    @KafkaListener(
            topics = KafkaTopics.FILTER_EVENTS,
            groupId = "routify-identity-service-webhook-filter-events",
            containerFactory = "webhookKafkaListenerContainerFactory"
    )
    public void onFilterEvent(DomainEvent event) {
        String eventType = mapFilterEvent(event);
        if (eventType != null) {
            dispatchForEvent(event.tenantId(), eventType, event);
        }
    }

    @KafkaListener(
            topics = KafkaTopics.CERT_EVENTS,
            groupId = "routify-identity-service-webhook-cert-events",
            containerFactory = "webhookKafkaListenerContainerFactory"
    )
    public void onCertEvent(DomainEvent event) {
        String eventType = mapCertEvent(event);
        if (eventType != null) {
            dispatchForEvent(event.tenantId(), eventType, event);
        }
    }

    @KafkaListener(
            topics = KafkaTopics.AI_FILTER_DECISIONS,
            groupId = "routify-identity-service-webhook-ai-decisions",
            containerFactory = "webhookKafkaListenerContainerFactory"
    )
    public void onAiFilterDecision(String payload) {
        try {
            Map<String, Object> data = objectMapper.readValue(payload,
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            String action = (String) data.get("action");
            String tenantIdStr = (String) data.get("tenantId");
            if (tenantIdStr == null) return;
            UUID tenantId = UUID.fromString(tenantIdStr);

            String eventType = switch (action) {
                case "BLOCK" -> WebhookEventType.AI_FILTER_BLOCKED.name();
                case "FLAG" -> WebhookEventType.AI_FILTER_FLAGGED.name();
                default -> null;
            };
            if (eventType != null) {
                dispatchRaw(tenantId, eventType, payload);
            }
        } catch (Exception e) {
            log.warn("Failed to process AI filter decision for webhooks: {}", e.getMessage());
        }
    }

    // ─── Event mapping ─────────────────────────────────────────────────────────

    private String mapRouteEvent(DomainEvent event) {
        return switch (event) {
            case DomainEvent.RouteCreated ignored   -> WebhookEventType.ROUTE_CREATED.name();
            case DomainEvent.RouteActivated ignored  -> WebhookEventType.ROUTE_ACTIVATED.name();
            case DomainEvent.RouteDeactivated ignored -> WebhookEventType.ROUTE_DEACTIVATED.name();
            case DomainEvent.RouteDeleted ignored    -> WebhookEventType.ROUTE_DELETED.name();
            case DomainEvent.RoutePromoted ignored   -> WebhookEventType.ROUTE_PROMOTED.name();
            default -> null;
        };
    }

    private String mapFilterEvent(DomainEvent event) {
        return switch (event) {
            case DomainEvent.FilterCreated ignored -> WebhookEventType.FILTER_CREATED.name();
            case DomainEvent.FilterUpdated ignored -> WebhookEventType.FILTER_UPDATED.name();
            case DomainEvent.FilterDeleted ignored -> WebhookEventType.FILTER_DELETED.name();
            default -> null;
        };
    }

    private String mapCertEvent(DomainEvent event) {
        return switch (event) {
            case DomainEvent.CertificateUploaded ignored -> WebhookEventType.CERT_UPLOADED.name();
            case DomainEvent.CertificateRevoked ignored  -> WebhookEventType.CERT_REVOKED.name();
            default -> null;
        };
    }

    // ─── Dispatch ──────────────────────────────────────────────────────────────

    private void dispatchForEvent(UUID tenantId, String eventType, DomainEvent event) {
        if (tenantId == null) return;
        try {
            String payloadJson = objectMapper.writeValueAsString(Map.of(
                    "eventType", eventType,
                    "timestamp", Instant.now().toString(),
                    "data", event));
            dispatchRaw(tenantId, eventType, payloadJson);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize webhook payload: {}", e.getMessage());
        }
    }

    private void dispatchRaw(UUID tenantId, String eventType, String payloadJson) {
        List<WebhookSubscription> subscriptions =
                webhookService.findActiveByTenantIdAndEventType(tenantId, eventType);

        for (WebhookSubscription sub : subscriptions) {
            log.debug("Dispatching webhook: subscription={} eventType={}", sub.getId(), eventType);
            webhookDispatcher.dispatch(sub, eventType, payloadJson);
        }
    }
}

