package io.routify.route.service;

import io.routify.common.domain.RouteEnvironment;
import io.routify.common.domain.RouteStatus;
import io.routify.common.event.DomainEvent;
import io.routify.common.event.KafkaTopics;
import io.routify.common.exception.RoutifyException;
import io.routify.route.config.CacheConfig;
import io.routify.route.dto.RouteStatusCount;
import io.routify.route.domain.FilterDefinition;
import io.routify.route.domain.Route;
import io.routify.route.domain.RouteFilter;
import io.routify.route.outbox.OutboxEventStore;
import io.routify.route.repository.FilterDefinitionRepository;
import io.routify.route.repository.RouteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Core Route management service.
 *
 * <p>Design principles:
 * <ul>
 *   <li>All writes publish domain events via the Outbox pattern</li>
 *   <li>Activation/deactivation triggers immediate gateway hot-reload via Kafka</li>
 *   <li>Uses Java 21 virtual threads — no blocking I/O concerns</li>
 *   <li>All mutating operations are @Transactional with the Outbox event in the same TX</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RouteService {

    private final RouteRepository routeRepository;
    private final FilterDefinitionRepository filterRepository;
    private final OutboxEventStore outboxStore;

    // ─── Queries ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<Route> findAll(UUID tenantId, RouteStatus status, Pageable pageable) {
        if (status != null) {
            return routeRepository.findAllByTenantIdAndStatus(tenantId, status, pageable);
        }
        return routeRepository.findAllByTenantId(tenantId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Route> findAll(UUID tenantId, RouteStatus status, RouteEnvironment environment, Pageable pageable) {
        if (status != null && environment != null) {
            return routeRepository.findAllByTenantIdAndStatusAndEnvironment(tenantId, status, environment, pageable);
        }
        if (environment != null) {
            return routeRepository.findAllByTenantIdAndEnvironment(tenantId, environment, pageable);
        }
        if (status != null) {
            return routeRepository.findAllByTenantIdAndStatus(tenantId, status, pageable);
        }
        return routeRepository.findAllByTenantId(tenantId, pageable);
    }

    /**
     * Paginates routes and eagerly initialises their {@code filters} collection within
     * the same Hibernate session.  Use this whenever the caller needs to access
     * {@code route.getFilters()} outside the transaction (e.g. in a mapper).
     *
     * <p>Two-query pattern:
     * <ol>
     *   <li>Standard paginated query — correct {@code totalElements} / {@code totalPages}.</li>
     *   <li>JOIN FETCH by the returned IDs — hydrates lazy collections without the
     *       HHH90003004 "HQL query should not use DISTINCT" / count-query warning.</li>
     * </ol>
     */
    @Transactional(readOnly = true)
    public Page<Route> findAllWithFilters(UUID tenantId, RouteStatus status, Pageable pageable) {
        Page<Route> page = findAll(tenantId, status, pageable);
        if (page.isEmpty()) {
            return page;
        }
        List<UUID> ids = page.getContent().stream().map(Route::getId).toList();
        routeRepository.findAllWithFiltersByIds(ids);
        return page;
    }

    @Transactional(readOnly = true)
    public Page<Route> findAllWithFilters(UUID tenantId, RouteStatus status, RouteEnvironment environment, Pageable pageable) {
        Page<Route> page = findAll(tenantId, status, environment, pageable);
        if (page.isEmpty()) {
            return page;
        }
        List<UUID> ids = page.getContent().stream().map(Route::getId).toList();
        routeRepository.findAllWithFiltersByIds(ids);
        return page;
    }

    @Transactional(readOnly = true)
    public Route findById(UUID id, UUID tenantId) {
        return routeRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new RoutifyException.NotFound("Route", id.toString()));
    }

    /**
     * Fetch a route by id with its {@code filters} collection eagerly initialized.
     * Use this whenever the route is mapped to a DTO outside a transaction boundary
     * (e.g. RabbitMQ handlers, async contexts).
     */
    @Transactional(readOnly = true)
    public Route findByIdWithFilters(UUID id, UUID tenantId) {
        return routeRepository.findByIdAndTenantIdWithFilters(id, tenantId)
                .orElseThrow(() -> new RoutifyException.NotFound("Route", id.toString()));
    }

    @Cacheable(CacheConfig.CACHE_GATEWAY_SNAPSHOT)
    @Transactional(readOnly = true)
    public List<Route> findAllActiveWithFilters() {
        return routeRepository.findAllActiveWithFilters();
    }

    @Transactional(readOnly = true)
    public List<RouteStatusCount> countByStatus(UUID tenantId) {
        return routeRepository.countByStatusForTenant(tenantId);
    }

    // ─── Commands ─────────────────────────────────────────────────────────────

    /**
     * Creates a new route in DRAFT status.
     * No gateway changes occur until the route is explicitly activated.
     */
    @Transactional
    public Route create(Route route, UUID tenantId, String createdBy) {
        if (routeRepository.existsByNameAndTenantIdAndEnvironment(route.getName(), tenantId, route.getEnvironment())) {
            throw new RoutifyException.Conflict(
                    "Route with name '%s' already exists in %s".formatted(route.getName(), route.getEnvironment()));
        }

        Route saved = routeRepository.save(route);

        outboxStore.store(
                new DomainEvent.RouteCreated(
                        UUID.randomUUID(), tenantId, saved.getId(),
                        saved.getName(), saved.getPathPattern(), saved.getMethods(),
                        Instant.now(), null, createdBy),
                KafkaTopics.ROUTE_EVENTS, tenantId);

        log.info("Route created: id={} name={} tenant={}", saved.getId(), saved.getName(), tenantId);
        return saved;
    }

    /**
     * Clones an existing route — creates a new DRAFT route with the same configuration
     * and filter chain, but a new ID and a "(copy)" name suffix.
     *
     * <p>The cloned route starts as DRAFT (version 1, no activatedAt).
     * Filter attachments are duplicated so the clone has its own independent filter chain.
     */
    @Transactional
    public Route clone(UUID sourceId, UUID tenantId, String createdBy) {
        Route source = findByIdWithFilters(sourceId, tenantId);

        String clonedName = source.getName() + " (copy)";
        // Ensure uniqueness — append counter if name already taken
        if (routeRepository.existsByNameAndTenantId(clonedName, tenantId)) {
            int counter = 2;
            String candidate;
            do {
                candidate = source.getName() + " (copy " + counter + ")";
                counter++;
            } while (routeRepository.existsByNameAndTenantId(candidate, tenantId));
            clonedName = candidate;
        }

        Route cloned = Route.builder()
                .tenantId(tenantId)
                .name(clonedName)
                .description(source.getDescription())
                .pathPattern(source.getPathPattern())
                .methods(source.getMethods())
                .upstreamUri(source.getUpstreamUri())
                .stripPrefix(source.getStripPrefix())
                .createdBy(createdBy)
                .extraConfig(source.getExtraConfig() != null ? new java.util.HashMap<>(source.getExtraConfig()) : null)
                .build();

        // No duplicate-path check here — cloned routes start as DRAFT and can share a path
        // with the source or other DRAFT/DISABLED routes.  Ambiguity is only possible in the
        // live gateway, so the check is enforced at activation time.

        Route saved = routeRepository.save(cloned);

        // Re-attach filters in same order/phase
        for (RouteFilter rf : source.getFilters()) {
            saved.attachFilter(rf.getFilterDefinition(), rf.getFilterOrder(), rf.getPhase());
        }
        saved = routeRepository.save(saved);

        outboxStore.store(
                new DomainEvent.RouteCloned(
                        UUID.randomUUID(), tenantId, sourceId, saved.getId(),
                        saved.getName(), Instant.now(), null, createdBy),
                KafkaTopics.ROUTE_EVENTS, tenantId);

        log.info("Route cloned: sourceId={} clonedId={} name={} tenant={}", sourceId, saved.getId(), saved.getName(), tenantId);
        return saved;
    }

    /**
     * Updates a route's metadata and configuration.
     * Active routes can be updated — changes apply on next request after activation.
     * If the route is ACTIVE, the update also triggers a gateway reload.
     */
    @Transactional
    public Route update(UUID routeId, UUID tenantId, RouteUpdateCommand cmd) {
        Route route = findById(routeId, tenantId);

        if (cmd.name() != null && !cmd.name().equals(route.getName())) {
            if (routeRepository.existsByNameAndTenantId(cmd.name(), tenantId)) {
                throw new RoutifyException.Conflict(
                        "Route with name '%s' already exists".formatted(cmd.name()));
            }
            route.setName(cmd.name());
        }
        if (cmd.description() != null) route.setDescription(cmd.description());
        if (cmd.pathPattern() != null) route.setPathPattern(cmd.pathPattern());
        if (cmd.methods() != null)     route.setMethods(cmd.methods());

        // For ACTIVE routes any path/method change is live immediately via gateway reload,
        // so we must guard against ambiguity right now.  For DRAFT/DISABLED routes the check
        // is deferred to activation time.
        if (route.isActive() && (cmd.pathPattern() != null || cmd.methods() != null)) {
            validateNoDuplicateRoute(tenantId, route.getPathPattern(), route.getMethods(), routeId);
        }

        if (cmd.upstreamUri() != null) route.setUpstreamUri(cmd.upstreamUri());
        if (cmd.stripPrefix() != null) route.setStripPrefix(cmd.stripPrefix());
        if (cmd.extraConfig() != null) route.setExtraConfig(cmd.extraConfig());

        Route saved = routeRepository.save(route);

        outboxStore.store(
                new DomainEvent.RouteUpdated(
                        UUID.randomUUID(), tenantId, saved.getId(),
                        saved.getName(), Instant.now(), null, null),
                KafkaTopics.ROUTE_EVENTS, tenantId);

        // If active, trigger immediate gateway reload for zero-downtime update
        if (route.isActive()) {
            outboxStore.store(
                    new DomainEvent.RouteActivated(
                            UUID.randomUUID(), tenantId, saved.getId(),
                            saved.getName(), saved.getPathPattern(), saved.getMethods(),
                            saved.getUpstreamUri(), Instant.now(), null, null),
                    KafkaTopics.GATEWAY_RELOAD, tenantId);
            log.info("Route updated with gateway reload: id={}", routeId);
        }

        return saved;
    }

    /**
     * Activates a route — makes it LIVE in the gateway with ZERO DOWNTIME.
     *
     * <p>The flow:
     * <ol>
     *   <li>Validate route can be activated</li>
     *   <li>Set status = ACTIVE, increment version</li>
     *   <li>Persist to DB + write RouteActivated event to Outbox (same TX)</li>
     *   <li>Outbox poller publishes to Kafka</li>
     *   <li>Gateway consumes the event and hot-reloads its RouteDefinitionLocator</li>
     *   <li>New requests immediately start using the updated route (no restart required)</li>
     * </ol>
     */
    @Transactional
    public Route activate(UUID routeId, UUID tenantId) {
        Route route = findById(routeId, tenantId);

        // Guard before going live: no other ACTIVE route under this tenant may own the same
        // (pathPattern, methods) slot — that would create routing ambiguity in the gateway.
        validateNoDuplicateRoute(tenantId, route.getPathPattern(), route.getMethods(), routeId);

        try {
            route.activate(); // throws if already ACTIVE
        } catch (IllegalStateException e) {
            throw new RoutifyException.Conflict(e.getMessage());
        }

        Route saved = routeRepository.save(route);

        outboxStore.store(
                new DomainEvent.RouteActivated(
                        UUID.randomUUID(), tenantId, saved.getId(),
                        saved.getName(), saved.getPathPattern(), saved.getMethods(),
                        saved.getUpstreamUri(), Instant.now(), null, null),
                KafkaTopics.ROUTE_EVENTS, tenantId);

        // This second event triggers the Gateway to reload — it's consumed by
        // routify-api-gateway's DynamicRouteRefreshListener
        outboxStore.store(
                new DomainEvent.GatewayReloadRequested(
                        UUID.randomUUID(), tenantId,
                        "Route %s activated".formatted(saved.getName()),
                        Instant.now(), null, null),
                KafkaTopics.GATEWAY_RELOAD, tenantId);

        log.info("Route activated: id={} name={} version={}", saved.getId(), saved.getName(), saved.getVersion());
        return saved;
    }

    /**
     * Deactivates a route — removes it from live gateway routing.
     * Zero downtime: in-flight requests to the route complete normally.
     */
    @Transactional
    public Route deactivate(UUID routeId, UUID tenantId) {
        Route route = findById(routeId, tenantId);

        try {
            route.deactivate();
        } catch (IllegalStateException e) {
            throw new RoutifyException.Conflict(e.getMessage());
        }

        Route saved = routeRepository.save(route);

        outboxStore.store(
                new DomainEvent.RouteDeactivated(
                        UUID.randomUUID(), tenantId, saved.getId(),
                        saved.getName(), Instant.now(), null, null),
                KafkaTopics.ROUTE_EVENTS, tenantId);

        outboxStore.store(
                new DomainEvent.GatewayReloadRequested(
                        UUID.randomUUID(), tenantId,
                        "Route %s deactivated".formatted(saved.getName()),
                        Instant.now(), null, null),
                KafkaTopics.GATEWAY_RELOAD, tenantId);

        log.info("Route deactivated: id={} name={}", saved.getId(), saved.getName());
        return saved;
    }

    /**
     * Soft-deletes a route by archiving it.
     * Active routes cannot be deleted — must be deactivated first.
     */
    @Transactional
    public void delete(UUID routeId, UUID tenantId) {
        Route route = findById(routeId, tenantId);

        if (route.isActive()) {
            throw new RoutifyException.Validation(
                    "Cannot delete an ACTIVE route. Deactivate it first.");
        }

        route.archive();
        routeRepository.save(route);

        outboxStore.store(
                new DomainEvent.RouteDeleted(
                        UUID.randomUUID(), tenantId, routeId, Instant.now(), null, null),
                KafkaTopics.ROUTE_EVENTS, tenantId);

        log.info("Route archived: id={}", routeId);
    }

    /**
     * Promotes a STAGING route to PRODUCTION.
     *
     * <p>The flow:
     * <ol>
     *   <li>Load the staging route — validate it is STAGING + ACTIVE</li>
     *   <li>Find or create the matching PRODUCTION route (same name + tenantId)</li>
     *   <li>Copy all config fields and filters from staging → production</li>
     *   <li>Increment production route version</li>
     *   <li>Archive the staging route</li>
     *   <li>Publish RoutePromoted + GatewayReloadRequested events</li>
     * </ol>
     */
    @Transactional
    public Route promoteRoute(UUID routeId, UUID tenantId, String actor) {
        Route staging = findByIdWithFilters(routeId, tenantId);

        if (staging.getEnvironment() != RouteEnvironment.STAGING) {
            throw new RoutifyException.Validation(
                    "Route '%s' is not a STAGING route — cannot promote".formatted(staging.getName()));
        }
        if (staging.getStatus() != RouteStatus.ACTIVE) {
            throw new RoutifyException.Validation(
                    "Only ACTIVE staging routes can be promoted (current: %s)".formatted(staging.getStatus()));
        }

        // Find or create the production counterpart
        Route production = routeRepository.findByNameAndTenantIdAndEnvironment(
                staging.getName(), tenantId, RouteEnvironment.PRODUCTION).orElse(null);

        if (production == null) {
            // Create new production route
            production = Route.builder()
                    .tenantId(tenantId)
                    .name(staging.getName())
                    .description(staging.getDescription())
                    .pathPattern(staging.getPathPattern())
                    .methods(staging.getMethods())
                    .upstreamUri(staging.getUpstreamUri())
                    .stripPrefix(staging.getStripPrefix())
                    .createdBy(actor)
                    .extraConfig(staging.getExtraConfig() != null ? new java.util.HashMap<>(staging.getExtraConfig()) : null)
                    .environment(RouteEnvironment.PRODUCTION)
                    .build();
        } else {
            // Update existing production route config
            production.setDescription(staging.getDescription());
            production.setPathPattern(staging.getPathPattern());
            production.setMethods(staging.getMethods());
            production.setUpstreamUri(staging.getUpstreamUri());
            production.setStripPrefix(staging.getStripPrefix());
            production.setExtraConfig(staging.getExtraConfig() != null ? new java.util.HashMap<>(staging.getExtraConfig()) : null);

            // Clear existing filters and re-attach from staging
            for (var existingFilter : new java.util.ArrayList<>(production.getFilters())) {
                production.detachFilter(existingFilter.getFilterDefinition().getId());
            }
        }

        production = routeRepository.save(production);

        // Re-attach filters from staging
        for (var rf : staging.getFilters()) {
            production.attachFilter(rf.getFilterDefinition(), rf.getFilterOrder(), rf.getPhase());
        }

        // Activate the production route (this increments version)
        if (production.getStatus() != RouteStatus.ACTIVE) {
            production.activate();
        } else {
            // Already active — just increment version for hot-reload
            production = routeRepository.save(production);
        }

        Route savedProduction = routeRepository.save(production);

        // Archive the staging route
        staging.archive();
        routeRepository.save(staging);

        // Publish RoutePromoted event for audit
        outboxStore.store(
                new DomainEvent.RoutePromoted(
                        UUID.randomUUID(), tenantId, staging.getId(), savedProduction.getId(),
                        staging.getName(), Instant.now(), null, actor),
                KafkaTopics.ROUTE_EVENTS, tenantId);

        // Trigger gateway reload
        outboxStore.store(
                new DomainEvent.GatewayReloadRequested(
                        UUID.randomUUID(), tenantId,
                        "Route %s promoted from STAGING to PRODUCTION".formatted(staging.getName()),
                        Instant.now(), null, actor),
                KafkaTopics.GATEWAY_RELOAD, tenantId);

        log.info("Route promoted: staging={} production={} name={} tenant={}",
                staging.getId(), savedProduction.getId(), staging.getName(), tenantId);
        return savedProduction;
    }

    // ─── Filter Management ────────────────────────────────────────────────────

    /**
     * Attaches a filter to a route.
     * If the route is ACTIVE, triggers an immediate gateway reload.
     *
     * <p>M2 fix: usage_count is incremented via atomic SQL
     * ({@code routify.increment_filter_usage()}) rather than the in-memory
     * {@code filter.incrementUsage()} pattern which was susceptible to a
     * lost-update race under concurrent requests.
     */
    @Transactional
    public Route attachFilter(UUID routeId, UUID filterId, int order, String phase, UUID tenantId) {
        Route route = findById(routeId, tenantId);
        FilterDefinition filter = filterRepository.findByIdAndTenantId(filterId, tenantId)
                .orElseThrow(() -> new RoutifyException.NotFound("FilterDefinition", filterId.toString()));

        try {
            route.attachFilter(filter, order, phase);
        } catch (IllegalArgumentException e) {
            throw new RoutifyException.Conflict(e.getMessage());
        }

        Route saved = routeRepository.save(route);

        // Atomic usage counter increment (M2 fix — replaces in-memory filter.incrementUsage())
        filterRepository.incrementUsageAtomic(filterId);

        outboxStore.store(
                new DomainEvent.FilterAttached(
                        UUID.randomUUID(), tenantId, routeId, filterId,
                        order, phase, Instant.now(), null, null),
                KafkaTopics.FILTER_EVENTS, tenantId);

        if (route.isActive()) {
            triggerGatewayReload(tenantId,
                    "Filter %s attached to active route %s".formatted(filter.getName(), route.getName()));
        }

        log.info("Filter attached: filterId={} routeId={} order={} phase={}", filterId, routeId, order, phase);
        return saved;
    }

    /**
     * Detaches a filter from a route.
     * If the route is ACTIVE, triggers an immediate gateway reload.
     *
     * <p>M2 fix: usage_count is decremented via atomic SQL
     * ({@code routify.decrement_filter_usage()}) rather than the in-memory
     * {@code filter.decrementUsage()} / {@code @PreRemove} pattern.
     */
    @Transactional
    public Route detachFilter(UUID routeId, UUID filterId, UUID tenantId) {
        Route route = findById(routeId, tenantId);

        try {
            route.detachFilter(filterId);
        } catch (IllegalArgumentException e) {
            throw new RoutifyException.NotFound("Filter attachment", filterId.toString());
        }

        Route saved = routeRepository.save(route);

        // Atomic usage counter decrement (M2 fix — replaces @PreRemove filter.decrementUsage())
        filterRepository.decrementUsageAtomic(filterId);

        outboxStore.store(
                new DomainEvent.FilterDetached(
                        UUID.randomUUID(), tenantId, routeId, filterId, Instant.now(), null, null),
                KafkaTopics.FILTER_EVENTS, tenantId);

        if (route.isActive()) {
            triggerGatewayReload(tenantId,
                    "Filter %s detached from active route %s".formatted(filterId, route.getName()));
        }

        log.info("Filter detached: filterId={} routeId={}", filterId, routeId);
        return saved;
    }

    private void triggerGatewayReload(UUID tenantId, String reason) {
        outboxStore.store(
                new DomainEvent.GatewayReloadRequested(
                        UUID.randomUUID(), tenantId, reason, Instant.now(), null, null),
                KafkaTopics.GATEWAY_RELOAD, tenantId);
    }

    /**
     * Ensures there is no other non-ARCHIVED route under the same tenant with the same
     * (pathPattern, methods) combination.  A duplicate would make request routing
     * ambiguous — the gateway cannot deterministically decide which route to use.
     *
     * @param excludeId when non-null, the route with this ID is excluded from the check
     *                  (used during updates so a route may keep its own path/methods).
     * @throws RoutifyException.Conflict if a conflicting route exists
     */
    private void validateNoDuplicateRoute(UUID tenantId, String pathPattern, String methods, UUID excludeId) {
        boolean duplicate = excludeId == null
                ? routeRepository.existsActiveByPathPatternAndMethodsAndTenantId(tenantId, pathPattern, methods)
                : routeRepository.existsActiveByPathPatternAndMethodsAndTenantIdExcluding(tenantId, pathPattern, methods, excludeId);
        if (duplicate) {
            throw new RoutifyException.Conflict(
                    "A route with path pattern '%s' and methods '%s' already exists for this tenant"
                            .formatted(pathPattern, methods));
        }
    }
}

