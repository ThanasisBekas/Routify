package gr.routify.audit.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.audit.domain.AuditLogEntry;
import gr.routify.audit.repository.AuditLogRepository;
import gr.routify.common.event.DomainEvent;
import gr.routify.common.event.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes all domain events from Kafka and persists them to the audit log.
 *
 * <p>This is an append-only consumer — events are NEVER updated or deleted
 * (except by retention policy). Guarantees a complete, immutable audit trail.
 *
 * <p>Uses Java 21 sealed interface pattern matching for exhaustive event handling.
 * Uses MANUAL_IMMEDIATE acknowledgment to ensure at-least-once delivery.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DomainEventAuditConsumer {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = {
                    KafkaTopics.ROUTE_EVENTS,
                    KafkaTopics.FILTER_EVENTS,
                    KafkaTopics.TENANT_EVENTS,
                    KafkaTopics.USER_EVENTS,
                    KafkaTopics.GATEWAY_RELOAD
            },
            groupId = "routify-audit-consumer",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void onDomainEvent(String eventJson,
                               @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                               Acknowledgment ack) {
        try {
            DomainEvent event = objectMapper.readValue(eventJson, DomainEvent.class);

            // Use Java 21 pattern matching to extract audit metadata
            AuditLogEntry entry = new AuditLogEntry(
                    event.eventId(),
                    event.tenantId(),
                    resolveEventType(event),
                    resolveAggregateType(event),
                    resolveAggregateId(event),
                    event.actor(),
                    eventJson,
                    event.correlationId(),
                    event.occurredAt()
            );

            auditLogRepository.save(entry);
            ack.acknowledge();

            log.debug("Audit entry recorded: type={} aggregateId={}",
                    entry.getEventType(), entry.getAggregateId());

        } catch (Exception e) {
            log.error("Failed to record audit entry from topic {}: {}", topic, e.getMessage(), e);
            // Do NOT acknowledge — will be retried by Kafka
            // After max retries, will go to DLQ
        }
    }

    private String resolveEventType(DomainEvent event) {
        return switch (event) {
            case DomainEvent.RouteCreated ignored          -> "ROUTE_CREATED";
            case DomainEvent.RouteCloned ignored           -> "ROUTE_CLONED";
            case DomainEvent.RouteUpdated ignored          -> "ROUTE_UPDATED";
            case DomainEvent.RouteActivated ignored        -> "ROUTE_ACTIVATED";
            case DomainEvent.RouteDeactivated ignored      -> "ROUTE_DEACTIVATED";
            case DomainEvent.RouteDeleted ignored          -> "ROUTE_DELETED";
            case DomainEvent.FilterCreated ignored         -> "FILTER_CREATED";
            case DomainEvent.FilterUpdated ignored         -> "FILTER_UPDATED";
            case DomainEvent.FilterDeleted ignored         -> "FILTER_DELETED";
            case DomainEvent.FilterAttached ignored        -> "FILTER_ATTACHED";
            case DomainEvent.FilterDetached ignored        -> "FILTER_DETACHED";
            case DomainEvent.TenantCreated ignored         -> "TENANT_CREATED";
            case DomainEvent.TenantUpdated ignored         -> "TENANT_UPDATED";
            case DomainEvent.TenantSuspended ignored       -> "TENANT_SUSPENDED";
            case DomainEvent.UserCreated ignored           -> "USER_CREATED";
            case DomainEvent.UserUpdated ignored           -> "USER_UPDATED";
            case DomainEvent.UserDeleted ignored           -> "USER_DELETED";
            case DomainEvent.CertRotated ignored           -> "CERT_ROTATED";
            case DomainEvent.GatewayReloadRequested ignored -> "GATEWAY_RELOAD_REQUESTED";
            case DomainEvent.GatewayConfigChanged ignored  -> "GATEWAY_CONFIG_CHANGED";
        };
    }

    private String resolveAggregateType(DomainEvent event) {
        return switch (event) {
            case DomainEvent.RouteCreated ignored          -> "ROUTE";
            case DomainEvent.RouteCloned ignored           -> "ROUTE";
            case DomainEvent.RouteUpdated ignored          -> "ROUTE";
            case DomainEvent.RouteActivated ignored        -> "ROUTE";
            case DomainEvent.RouteDeactivated ignored      -> "ROUTE";
            case DomainEvent.RouteDeleted ignored          -> "ROUTE";
            case DomainEvent.FilterCreated ignored         -> "FILTER";
            case DomainEvent.FilterUpdated ignored         -> "FILTER";
            case DomainEvent.FilterDeleted ignored         -> "FILTER";
            case DomainEvent.FilterAttached ignored        -> "FILTER";
            case DomainEvent.FilterDetached ignored        -> "FILTER";
            case DomainEvent.TenantCreated ignored         -> "TENANT";
            case DomainEvent.TenantUpdated ignored         -> "TENANT";
            case DomainEvent.TenantSuspended ignored       -> "TENANT";
            case DomainEvent.UserCreated ignored           -> "USER";
            case DomainEvent.UserUpdated ignored           -> "USER";
            case DomainEvent.UserDeleted ignored           -> "USER";
            case DomainEvent.CertRotated ignored           -> "CERTIFICATE";
            case DomainEvent.GatewayReloadRequested ignored -> "GATEWAY";
            case DomainEvent.GatewayConfigChanged ignored  -> "GATEWAY";
        };
    }

    private String resolveAggregateId(DomainEvent event) {
        return switch (event) {
            case DomainEvent.RouteCreated r               -> r.routeId().toString();
            case DomainEvent.RouteCloned r                -> r.clonedRouteId().toString();
            case DomainEvent.RouteUpdated r               -> r.routeId().toString();
            case DomainEvent.RouteActivated r             -> r.routeId().toString();
            case DomainEvent.RouteDeactivated r           -> r.routeId().toString();
            case DomainEvent.RouteDeleted r               -> r.routeId().toString();
            case DomainEvent.FilterCreated f              -> f.filterId().toString();
            case DomainEvent.FilterUpdated f              -> f.filterId().toString();
            case DomainEvent.FilterDeleted f              -> f.filterId().toString();
            case DomainEvent.FilterAttached f             -> f.filterId().toString();
            case DomainEvent.FilterDetached f             -> f.filterId().toString();
            case DomainEvent.TenantCreated t              -> t.tenantId().toString();
            case DomainEvent.TenantUpdated t              -> t.tenantId().toString();
            case DomainEvent.TenantSuspended t            -> t.tenantId().toString();
            case DomainEvent.UserCreated u                -> u.userId().toString();
            case DomainEvent.UserUpdated u                -> u.userId().toString();
            case DomainEvent.UserDeleted u                -> u.userId().toString();
            case DomainEvent.CertRotated c                -> c.routeId().toString();
            case DomainEvent.GatewayReloadRequested g     -> g.tenantId().toString();
            case DomainEvent.GatewayConfigChanged g       -> g.tenantId().toString();
        };
    }
}

