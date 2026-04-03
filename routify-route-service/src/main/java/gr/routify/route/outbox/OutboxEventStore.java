package gr.routify.route.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.common.event.DomainEvent;
import gr.routify.route.domain.OutboxEvent;
import gr.routify.route.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Service for creating Outbox entries from domain events.
 * Always called within an existing transaction so the event and domain change
 * are committed atomically.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxEventStore {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * Stores a domain event in the outbox table within the current transaction.
     *
     * @param event   the domain event to store
     * @param topic   the Kafka topic to publish to
     * @param tenantId the tenant ID used as the partition key
     */
    public void store(DomainEvent event, String topic, UUID tenantId) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            String aggregateType = resolveAggregateType(event);
            String aggregateId   = resolveAggregateId(event);

            OutboxEvent outboxEvent = new OutboxEvent(
                    aggregateType,
                    aggregateId,
                    event.getClass().getSimpleName(),
                    topic,
                    tenantId != null ? tenantId.toString() : null,
                    payload
            );

            repository.save(outboxEvent);
            log.debug("Stored outbox event: type={} aggregateId={}",
                    outboxEvent.getEventType(), aggregateId);

        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize domain event: " + event.getClass().getSimpleName(), e);
        }
    }

    private String resolveAggregateType(DomainEvent event) {
        return switch (event) {
            case DomainEvent.RouteCreated r    -> "Route";
            case DomainEvent.RouteCloned r     -> "Route";
            case DomainEvent.RouteUpdated r    -> "Route";
            case DomainEvent.RouteActivated r  -> "Route";
            case DomainEvent.RouteDeactivated r-> "Route";
            case DomainEvent.RouteDeleted r    -> "Route";
            case DomainEvent.FilterCreated f   -> "FilterDefinition";
            case DomainEvent.FilterUpdated f   -> "FilterDefinition";
            case DomainEvent.FilterDeleted f   -> "FilterDefinition";
            case DomainEvent.FilterAttached f  -> "RouteFilter";
            case DomainEvent.FilterDetached f  -> "RouteFilter";
            case DomainEvent.TenantCreated t   -> "Tenant";
            case DomainEvent.TenantUpdated t   -> "Tenant";
            case DomainEvent.TenantSuspended t -> "Tenant";
            case DomainEvent.UserCreated u     -> "User";
            case DomainEvent.UserUpdated u     -> "User";
            case DomainEvent.UserDeleted u     -> "User";
            case DomainEvent.CertRotated c     -> "Certificate";
            case DomainEvent.GatewayReloadRequested g -> "Gateway";
            case DomainEvent.GatewayConfigChanged g   -> "GatewayConfig";
            case DomainEvent.Unknown unknown -> "Unknown";
        };
    }

    private String resolveAggregateId(DomainEvent event) {
        return switch (event) {
            case DomainEvent.RouteCreated r    -> r.routeId().toString();
            case DomainEvent.RouteCloned r     -> r.clonedRouteId().toString();
            case DomainEvent.RouteUpdated r    -> r.routeId().toString();
            case DomainEvent.RouteActivated r  -> r.routeId().toString();
            case DomainEvent.RouteDeactivated r-> r.routeId().toString();
            case DomainEvent.RouteDeleted r    -> r.routeId().toString();
            case DomainEvent.FilterCreated f   -> f.filterId().toString();
            case DomainEvent.FilterUpdated f   -> f.filterId().toString();
            case DomainEvent.FilterDeleted f   -> f.filterId().toString();
            case DomainEvent.FilterAttached f  -> f.routeId().toString();
            case DomainEvent.FilterDetached f  -> f.routeId().toString();
            case DomainEvent.TenantCreated t   -> t.tenantId().toString();
            case DomainEvent.TenantUpdated t   -> t.tenantId().toString();
            case DomainEvent.TenantSuspended t -> t.tenantId().toString();
            case DomainEvent.UserCreated u     -> u.userId().toString();
            case DomainEvent.UserUpdated u     -> u.userId().toString();
            case DomainEvent.UserDeleted u     -> u.userId().toString();
            case DomainEvent.CertRotated c     -> c.routeId().toString();
            case DomainEvent.GatewayReloadRequested g -> g.tenantId() != null ? g.tenantId().toString() : "platform";
            case DomainEvent.GatewayConfigChanged g   -> g.section() != null ? g.section() : "global";
            case DomainEvent.Unknown u -> u.tenantId() != null ? u.tenantId().toString() : "platform";
        };
    }
}

