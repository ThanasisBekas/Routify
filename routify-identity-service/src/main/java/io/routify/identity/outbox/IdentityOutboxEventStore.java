package io.routify.identity.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.routify.common.event.DomainEvent;
import io.routify.identity.domain.IdentityOutboxEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Stores domain events in the identity-service Outbox table.
 *
 * <p>Must always be called from within an active {@code @Transactional} context so that
 * the entity mutation and the outbox entry are committed atomically. This is the key
 * invariant that eliminates the dual-write risk from the previous direct Kafka publish pattern.
 *
 * <p>Usage example:
 * <pre>{@code
 * @Transactional
 * public Tenant create(CreateTenantRequest request) {
 *     Tenant saved = tenantRepository.save(tenant);
 *     outboxStore.store(new DomainEvent.TenantCreated(...), KafkaTopics.TENANT_EVENTS, saved.getId());
 *     return saved;          // both DB writes committed in ONE transaction
 * }
 * }</pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdentityOutboxEventStore {

    private final IdentityOutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * Persists a domain event as a PENDING outbox entry within the current transaction.
     *
     * @param event    the domain event to store
     * @param topic    the Kafka topic to publish to
     * @param tenantId the partition key (may be null for platform-wide events)
     */
    public void store(DomainEvent event, String topic, UUID tenantId) {
        try {
            String payload       = objectMapper.writeValueAsString(event);
            String aggregateType = resolveAggregateType(event);
            String aggregateId   = resolveAggregateId(event);

            IdentityOutboxEvent outboxEvent = new IdentityOutboxEvent(
                    aggregateType,
                    aggregateId,
                    event.getClass().getSimpleName(),
                    topic,
                    tenantId != null ? tenantId.toString() : null,
                    payload
            );

            repository.save(outboxEvent);

            log.debug("Identity outbox: queued {} aggregateId={}",
                    outboxEvent.getEventType(), aggregateId);

        } catch (JsonProcessingException e) {
            // Serialisation failure is a programming error, not a transient condition.
            throw new IllegalStateException(
                    "Failed to serialize domain event to outbox: " + event.getClass().getSimpleName(), e);
        }
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private String resolveAggregateType(DomainEvent event) {
        return switch (event) {
            case DomainEvent.TenantCreated ignored   -> "Tenant";
            case DomainEvent.TenantUpdated ignored   -> "Tenant";
            case DomainEvent.TenantSuspended ignored -> "Tenant";
            case DomainEvent.UserCreated ignored     -> "User";
            case DomainEvent.UserUpdated ignored     -> "User";
            case DomainEvent.UserDeleted ignored     -> "User";
            case DomainEvent.ApiKeyCreated ignored   -> "API_KEY";
            case DomainEvent.ApiKeyRevoked ignored   -> "API_KEY";
            default -> event.getClass().getSimpleName();
        };
    }

    private String resolveAggregateId(DomainEvent event) {
        return switch (event) {
            case DomainEvent.TenantCreated t   -> t.tenantId().toString();
            case DomainEvent.TenantUpdated t   -> t.tenantId().toString();
            case DomainEvent.TenantSuspended t -> t.tenantId().toString();
            case DomainEvent.UserCreated u     -> u.userId().toString();
            case DomainEvent.UserUpdated u     -> u.userId().toString();
            case DomainEvent.UserDeleted u     -> u.userId().toString();
            case DomainEvent.ApiKeyCreated a   -> a.apiKeyId().toString();
            case DomainEvent.ApiKeyRevoked a   -> a.apiKeyId().toString();
            default -> event.tenantId() != null ? event.tenantId().toString() : "platform";
        };
    }
}

