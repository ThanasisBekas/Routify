package io.routify.route.service;

import io.routify.common.event.DomainEvent;
import io.routify.common.event.KafkaTopics;
import io.routify.common.exception.RoutifyException;
import io.routify.route.domain.FilterDefinition;
import io.routify.route.outbox.OutboxEventStore;
import io.routify.route.repository.FilterDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Filter Definition CRUD service.
 *
 * <p>Filter definitions are reusable building blocks that can be attached
 * to any number of routes. They encapsulate configuration for a specific
 * filter type (rate limiter, JWT validator, JOLT transformer, etc.).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FilterDefinitionService {

    private final FilterDefinitionRepository repository;
    private final OutboxEventStore outboxStore;

    @Transactional(readOnly = true)
    public Page<FilterDefinition> findAll(UUID tenantId, Pageable pageable) {
        return repository.findAllByTenantId(tenantId, pageable);
    }

    @Transactional(readOnly = true)
    public FilterDefinition findById(UUID id, UUID tenantId) {
        return repository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new RoutifyException.NotFound("FilterDefinition", id.toString()));
    }

    @Transactional
    public FilterDefinition create(FilterDefinition filter, UUID tenantId) {
        if (repository.existsByNameAndTenantId(filter.getName(), tenantId)) {
            throw new RoutifyException.Conflict(
                    "FilterDefinition with name '%s' already exists".formatted(filter.getName()));
        }

        FilterDefinition saved = repository.save(filter);

        outboxStore.store(
                new DomainEvent.FilterCreated(
                        UUID.randomUUID(), tenantId, saved.getId(),
                        saved.getName(), saved.getFilterType().name(),
                        Instant.now(), null, null),
                KafkaTopics.FILTER_EVENTS, tenantId);

        log.info("FilterDefinition created: id={} type={}", saved.getId(), saved.getFilterType());
        return saved;
    }

    @Transactional
    public FilterDefinition update(UUID id, UUID tenantId, FilterDefinition updates) {
        FilterDefinition existing = findById(id, tenantId);

        if (existing.isSystemManaged()) {
            throw new RoutifyException.Forbidden("System-managed filters cannot be modified");
        }

        if (updates.getName() != null && !updates.getName().equals(existing.getName())) {
            if (repository.existsByNameAndTenantId(updates.getName(), tenantId)) {
                throw new RoutifyException.Conflict(
                        "FilterDefinition with name '%s' already exists".formatted(updates.getName()));
            }
            existing.setName(updates.getName());
        }
        if (updates.getDescription() != null)  existing.setDescription(updates.getDescription());
        if (updates.getConfig() != null)       existing.setConfig(updates.getConfig());

        FilterDefinition saved = repository.save(existing);

        outboxStore.store(
                new DomainEvent.FilterUpdated(
                        UUID.randomUUID(), tenantId, saved.getId(),
                        saved.getName(), Instant.now(), null, null),
                KafkaTopics.FILTER_EVENTS, tenantId);

        // If filter is in use by active routes, trigger gateway reload
        if (existing.isInUse()) {
            outboxStore.store(
                    new DomainEvent.GatewayReloadRequested(
                            UUID.randomUUID(), tenantId,
                            "FilterDefinition %s updated".formatted(saved.getName()),
                            Instant.now(), null, null),
                    KafkaTopics.GATEWAY_RELOAD, tenantId);
        }

        return saved;
    }

    @Transactional
    public void delete(UUID id, UUID tenantId) {
        FilterDefinition filter = findById(id, tenantId);

        if (filter.isSystemManaged()) {
            throw new RoutifyException.Forbidden("System-managed filters cannot be deleted");
        }
        if (filter.isInUse()) {
            throw new RoutifyException.Conflict(
                    "FilterDefinition is in use by %d route(s). Detach from all routes first."
                            .formatted(filter.getUsageCount()));
        }

        repository.delete(filter);

        outboxStore.store(
                new DomainEvent.FilterDeleted(
                        UUID.randomUUID(), tenantId, id, Instant.now(), null, null),
                KafkaTopics.FILTER_EVENTS, tenantId);

        log.info("FilterDefinition deleted: id={}", id);
    }
}

