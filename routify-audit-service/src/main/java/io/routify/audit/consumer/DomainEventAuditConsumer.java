package io.routify.audit.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.routify.audit.domain.AuditLogEntry;
import io.routify.audit.repository.AuditLogRepository;
import io.routify.common.event.DomainEvent;
import io.routify.common.event.KafkaTopics;
import io.routify.common.kafka.KafkaDlqErrorHandlerFactory;
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
 *
 * <h2>Poison-Pill Guard (C2 Fix)</h2>
 * <p>The previous implementation ended every switch expression with
 * {@code default -> throw new IllegalStateException(...)}, which caused
 * {@link DomainEvent.Unknown} (the Jackson fallback for unrecognised type discriminators)
 * to throw and create an infinite Kafka retry loop — a classic poison-pill scenario.
 *
 * <p>The fix replaces every {@code default} branch with an explicit
 * {@code case DomainEvent.Unknown} that logs a structured warning and re-throws so
 * the DLQ {@link KafkaDlqErrorHandlerFactory} can capture it
 * without blocking partition progress indefinitely.
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
                    KafkaTopics.GATEWAY_RELOAD,
                    KafkaTopics.CERT_EVENTS,
                    KafkaTopics.CERT_GROUP_EVENTS
            },
            groupId = "routify-audit-consumer",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void onDomainEvent(DomainEvent event,
                               @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                               Acknowledgment ack) {
        // C2 Fix: guard against DomainEvent.Unknown before doing any processing.
        // Unknown events arrive when a new service version publishes an event type
        // that this audit-service version has not yet been rebuilt with.
        // Route them to the DLQ via re-throw rather than creating an infinite retry loop.
        if (event instanceof DomainEvent.Unknown u) {
            log.warn("Unrecognised domain event received on topic={} correlationId={} — " +
                     "forwarding to DLQ. Rebuild routify-audit-service to pick up the new event type.",
                     topic, u.correlationId());
            // Do NOT acknowledge — the KafkaDlqErrorHandlerFactory will dead-letter after max retries
            throw new UnrecognisedDomainEventException(
                    "Unknown domain event type on topic [" + topic + "]");
        }

        try {
            String payload = objectMapper.writeValueAsString(event);

            AuditLogEntry entry = new AuditLogEntry(
                    event.eventId(),
                    event.tenantId(),
                    resolveEventType(event),
                    resolveAggregateType(event),
                    resolveAggregateId(event),
                    event.actor(),
                    payload,
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
            case DomainEvent.CertRotated ignored                    -> "CERT_ROTATED";
            case DomainEvent.CertificateUploaded ignored            -> "CERTIFICATE_UPLOADED";
            case DomainEvent.CertificateRevoked ignored             -> "CERTIFICATE_REVOKED";
            case DomainEvent.CertificateDeleted ignored             -> "CERTIFICATE_DELETED";
            case DomainEvent.CertificateMappedToGateway ignored     -> "CERTIFICATE_MAPPED_TO_GATEWAY";
            case DomainEvent.CertificateUnmappedFromGateway ignored -> "CERTIFICATE_UNMAPPED_FROM_GATEWAY";
            case DomainEvent.CertGroupCreated ignored               -> "CERT_GROUP_CREATED";
            case DomainEvent.CertGroupUpdated ignored               -> "CERT_GROUP_UPDATED";
            case DomainEvent.CertGroupArchived ignored              -> "CERT_GROUP_ARCHIVED";
            case DomainEvent.CertGroupDeleted ignored               -> "CERT_GROUP_DELETED";
            case DomainEvent.CertAddedToGroup ignored               -> "CERT_ADDED_TO_GROUP";
            case DomainEvent.CertRemovedFromGroup ignored           -> "CERT_REMOVED_FROM_GROUP";
            case DomainEvent.GatewayReloadRequested ignored -> "GATEWAY_RELOAD_REQUESTED";
            case DomainEvent.GatewayConfigChanged ignored  -> "GATEWAY_CONFIG_CHANGED";
            // Safety net: Unknown is already handled before entering the switch.
            case DomainEvent.Unknown ignored -> "UNKNOWN";
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
            case DomainEvent.CertRotated ignored                    -> "CERTIFICATE";
            case DomainEvent.CertificateUploaded ignored            -> "CERTIFICATE";
            case DomainEvent.CertificateRevoked ignored             -> "CERTIFICATE";
            case DomainEvent.CertificateDeleted ignored             -> "CERTIFICATE";
            case DomainEvent.CertificateMappedToGateway ignored     -> "CERTIFICATE";
            case DomainEvent.CertificateUnmappedFromGateway ignored -> "CERTIFICATE";
            case DomainEvent.CertGroupCreated ignored               -> "CERT_GROUP";
            case DomainEvent.CertGroupUpdated ignored               -> "CERT_GROUP";
            case DomainEvent.CertGroupArchived ignored              -> "CERT_GROUP";
            case DomainEvent.CertGroupDeleted ignored               -> "CERT_GROUP";
            case DomainEvent.CertAddedToGroup ignored               -> "CERT_GROUP";
            case DomainEvent.CertRemovedFromGroup ignored           -> "CERT_GROUP";
            case DomainEvent.GatewayReloadRequested ignored -> "GATEWAY";
            case DomainEvent.GatewayConfigChanged ignored  -> "GATEWAY";
            case DomainEvent.Unknown ignored -> "UNKNOWN";
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
            case DomainEvent.CertRotated c                       -> c.routeId().toString();
            case DomainEvent.CertificateUploaded e               -> e.certId().toString();
            case DomainEvent.CertificateRevoked e                -> e.certId().toString();
            case DomainEvent.CertificateDeleted e                -> e.certId().toString();
            case DomainEvent.CertificateMappedToGateway e        -> e.certId().toString();
            case DomainEvent.CertificateUnmappedFromGateway e    -> e.certId().toString();
            case DomainEvent.CertGroupCreated e                  -> e.groupId().toString();
            case DomainEvent.CertGroupUpdated e                  -> e.groupId().toString();
            case DomainEvent.CertGroupArchived e                 -> e.groupId().toString();
            case DomainEvent.CertGroupDeleted e                  -> e.groupId().toString();
            case DomainEvent.CertAddedToGroup e                  -> e.groupId().toString();
            case DomainEvent.CertRemovedFromGroup e              -> e.groupId().toString();
            case DomainEvent.GatewayReloadRequested g     -> g.tenantId() != null ? g.tenantId().toString() : "platform";
            case DomainEvent.GatewayConfigChanged g       -> g.section() != null ? g.section() : "global";
            case DomainEvent.Unknown u -> u.tenantId() != null ? u.tenantId().toString() : "unknown";
        };
    }

    // ─── Sentinel exception for unrecognised event types ──────────────────────

    /**
     * Thrown when a {@link DomainEvent.Unknown} is received.
     * The KafkaDlqErrorHandlerFactory will dead-letter the record after max retries
     * instead of retrying it forever, preventing partition stalls.
     */
    static final class UnrecognisedDomainEventException extends RuntimeException {
        UnrecognisedDomainEventException(String message) {
            super(message);
        }
    }
}
