package io.routify.admin.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.routify.admin.dto.CreateFilterRequest;
import io.routify.admin.dto.CreateRouteRequest;
import io.routify.admin.dto.UpdateFilterRequest;
import io.routify.admin.dto.UpdateRouteRequest;
import io.routify.admin.service.FilterConfigValidator;
import io.routify.common.client.AmqpServiceClientSupport;
import io.routify.common.client.KafkaServiceClientSupport;
import io.routify.common.domain.FilterType;
import io.routify.common.event.CommandEvent;
import io.routify.common.event.KafkaTopics;
import io.routify.common.event.QueryRequest;
import io.routify.common.event.QueryResponse;
import io.routify.common.event.RabbitTopology;
import io.routify.common.observability.RoutifyMetrics;
import io.routify.common.exception.RoutifyException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin-API messaging client for route and filter operations.
 *
 * <p><b>Queries</b> (read) go via RabbitMQ request/reply to routify-route-service.
 * <b>Commands</b> (write) are published as Kafka events to the command topics
 * and consumed by routify-route-service, which executes the mutation and publishes
 * the resulting domain event back to Kafka.
 *
 * <p>This is the ONLY path the dashboard uses to manage routes and filters.
 * There are no direct HTTP calls between admin-api and route-service.
 */
@Slf4j
@Component
public class RouteFilterMessagingClient extends AmqpServiceClientSupport {

    private final KafkaServiceClientSupport kafka;
    private final FilterConfigValidator filterConfigValidator;

    public RouteFilterMessagingClient(RabbitTemplate rabbitTemplate,
                                      ObjectMapper objectMapper,
                                      KafkaTemplate<String, Object> kafkaTemplate,
                                      RoutifyMetrics metrics,
                                      FilterConfigValidator filterConfigValidator) {
        super(rabbitTemplate, objectMapper, RabbitTopology.EXCHANGE_ROUTE_SERVICE, "admin-api", metrics);
        this.kafka = new KafkaServiceClientSupport(kafkaTemplate, "admin-api") {};
        this.filterConfigValidator = filterConfigValidator;
    }

    // ─── Route Queries (RabbitMQ) ─────────────────────────────────────────────

    @CircuitBreaker(name = "route-service", fallbackMethod = "queryRoutesFallback")
    public QueryResponse.RoutesPage queryRoutes(UUID tenantId, String status, String environment,
                                                int page, int size, String sortBy, String sortDir) {
        try {
            return rpc(RabbitTopology.RK_ROUTES_QUERY,
                    new QueryRequest.RoutesQuery(tenantId, status, environment, page, size, sortBy, sortDir),
                    QueryResponse.RoutesPage.class);
        } catch (Exception e) {
            log.error("queryRoutes failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    @SuppressWarnings("unused")
    private QueryResponse.RoutesPage queryRoutesFallback(UUID tenantId, String status, String environment,
                                                         int page, int size, String sortBy, String sortDir,
                                                         Throwable t) {
        log.warn("queryRoutes circuit open or timed out: {}", t.getMessage());
        return new QueryResponse.RoutesPage(java.util.List.of(), 0L, 0, page, size);
    }

    @CircuitBreaker(name = "route-service", fallbackMethod = "getRouteFallback")
    public QueryResponse.RouteDetail getRoute(UUID id, UUID tenantId) {
        try {
            return rpc(RabbitTopology.RK_ROUTES_GET,
                    new QueryRequest.RouteGet(id, tenantId),
                    QueryResponse.RouteDetail.class);
        } catch (Exception e) {
            log.error("getRoute failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    @SuppressWarnings("unused")
    private QueryResponse.RouteDetail getRouteFallback(UUID id, UUID tenantId, Throwable t) {
        log.warn("getRoute circuit open or timed out: {}", t.getMessage());
        return null;
    }

    @CircuitBreaker(name = "route-service", fallbackMethod = "cloneRouteFallback")
    public QueryResponse.RouteDetail cloneRoute(UUID sourceId, UUID tenantId, String userId) {
        try {
            return rpc(RabbitTopology.RK_ROUTES_CLONE,
                    new QueryRequest.RouteClone(sourceId, tenantId, userId),
                    QueryResponse.RouteDetail.class);
        } catch (Exception e) {
            log.error("cloneRoute failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    @SuppressWarnings("unused")
    private QueryResponse.RouteDetail cloneRouteFallback(UUID sourceId, UUID tenantId, String userId, Throwable t) {
        log.warn("cloneRoute circuit open or timed out: {}", t.getMessage());
        return null;
    }

    // ─── Filter Queries (RabbitMQ) ────────────────────────────────────────────

    @CircuitBreaker(name = "route-service", fallbackMethod = "queryFiltersFallback")
    public QueryResponse.FiltersPage queryFilters(UUID tenantId, int page, int size,
                                                  String sortBy, String sortDir) {
        try {
            return rpc(RabbitTopology.RK_FILTERS_QUERY,
                    new QueryRequest.FiltersQuery(tenantId, page, size, sortBy, sortDir),
                    QueryResponse.FiltersPage.class);
        } catch (Exception e) {
            log.error("queryFilters failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    @SuppressWarnings("unused")
    private QueryResponse.FiltersPage queryFiltersFallback(UUID tenantId, int page, int size,
                                                           String sortBy, String sortDir, Throwable t) {
        log.warn("queryFilters circuit open or timed out: {}", t.getMessage());
        return new QueryResponse.FiltersPage(java.util.List.of(), 0L, 0, page, size);
    }

    @CircuitBreaker(name = "route-service", fallbackMethod = "getFilterFallback")
    public QueryResponse.FilterDetail getFilter(UUID id, UUID tenantId) {
        try {
            return rpc(RabbitTopology.RK_FILTERS_GET,
                    new QueryRequest.FilterGet(id, tenantId),
                    QueryResponse.FilterDetail.class);
        } catch (Exception e) {
            log.error("getFilter failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    @SuppressWarnings("unused")
    private QueryResponse.FilterDetail getFilterFallback(UUID id, UUID tenantId, Throwable t) {
        log.warn("getFilter circuit open or timed out: {}", t.getMessage());
        return null;
    }

    @CircuitBreaker(name = "route-service", fallbackMethod = "queryDeprecatedFilterUsageFallback")
    public QueryResponse.DeprecatedFilterUsageResult queryDeprecatedFilterUsage(UUID tenantId) {
        try {
            return rpc(RabbitTopology.RK_FILTERS_DEPRECATED_USAGE,
                    new QueryRequest.DeprecatedFilterUsage(tenantId),
                    QueryResponse.DeprecatedFilterUsageResult.class);
        } catch (Exception e) {
            log.error("queryDeprecatedFilterUsage failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    @SuppressWarnings("unused")
    private QueryResponse.DeprecatedFilterUsageResult queryDeprecatedFilterUsageFallback(UUID tenantId, Throwable t) {
        log.warn("queryDeprecatedFilterUsage circuit open or timed out: {}", t.getMessage());
        return new QueryResponse.DeprecatedFilterUsageResult(0, java.util.Map.of(), java.util.List.of());
    }

    // ─── Route Commands (Kafka) ───────────────────────────────────────────────

    public void sendCreateRoute(UUID tenantId, String actor, CreateRouteRequest req) {
        kafka.publishCommand(KafkaTopics.ROUTE_COMMANDS, new CommandEvent.CreateRoute(
                UUID.randomUUID(), tenantId, actor, Instant.now(),
                req.name(), req.description(), req.pathPattern(),
                req.methods(), req.upstreamUri(), req.stripPrefix(),
                req.extraConfig(), req.environment()));
    }

    public void sendUpdateRoute(UUID id, UUID tenantId, String actor, UpdateRouteRequest req) {
        kafka.publishCommand(KafkaTopics.ROUTE_COMMANDS, new CommandEvent.UpdateRoute(
                UUID.randomUUID(), tenantId, actor, Instant.now(),
                id,
                req.name(), req.description(), req.pathPattern(),
                req.methods(), req.upstreamUri(), req.stripPrefix(),
                req.extraConfig()));
    }

    public void sendActivateRoute(UUID id, UUID tenantId, String actor) {
        kafka.publishCommand(KafkaTopics.ROUTE_COMMANDS,
                new CommandEvent.ActivateRoute(UUID.randomUUID(), tenantId, actor, Instant.now(), id));
    }

    public void sendDeactivateRoute(UUID id, UUID tenantId, String actor) {
        kafka.publishCommand(KafkaTopics.ROUTE_COMMANDS,
                new CommandEvent.DeactivateRoute(UUID.randomUUID(), tenantId, actor, Instant.now(), id));
    }

    public void sendDeleteRoute(UUID id, UUID tenantId, String actor) {
        kafka.publishCommand(KafkaTopics.ROUTE_COMMANDS,
                new CommandEvent.DeleteRoute(UUID.randomUUID(), tenantId, actor, Instant.now(), id));
    }

    public void sendPromoteRoute(UUID routeId, UUID tenantId, String actor) {
        kafka.publishCommand(KafkaTopics.ROUTE_COMMANDS,
                new CommandEvent.PromoteRoute(UUID.randomUUID(), tenantId, actor, Instant.now(), routeId));
    }

    public void sendCreateStagingRevision(UUID routeId, UUID tenantId, String actor) {
        kafka.publishCommand(KafkaTopics.ROUTE_COMMANDS,
                new CommandEvent.CreateStagingRevision(UUID.randomUUID(), tenantId, actor, Instant.now(), routeId));
    }

    // ─── Canary Commands (Kafka) ───────────────────────────────────────────────

    public void sendDeployCanary(UUID routeId, UUID tenantId, String actor,
                                 String canaryUpstreamUri, int trafficWeight,
                                 double autoRollbackThreshold,
                                 java.util.Map<String, Object> canaryExtraConfig) {
        kafka.publishCommand(KafkaTopics.ROUTE_COMMANDS,
                new CommandEvent.DeployCanary(UUID.randomUUID(), tenantId, actor, Instant.now(),
                        routeId, canaryUpstreamUri, trafficWeight, autoRollbackThreshold, canaryExtraConfig));
    }

    public void sendPromoteCanary(UUID routeId, UUID tenantId, String actor) {
        kafka.publishCommand(KafkaTopics.ROUTE_COMMANDS,
                new CommandEvent.PromoteCanary(UUID.randomUUID(), tenantId, actor, Instant.now(), routeId));
    }

    public void sendRollbackCanary(UUID routeId, UUID tenantId, String actor, String reason) {
        kafka.publishCommand(KafkaTopics.ROUTE_COMMANDS,
                new CommandEvent.RollbackCanary(UUID.randomUUID(), tenantId, actor, Instant.now(), routeId, reason));
    }

    public void sendAdjustCanaryWeight(UUID routeId, UUID tenantId, String actor, int newWeight) {
        kafka.publishCommand(KafkaTopics.ROUTE_COMMANDS,
                new CommandEvent.AdjustCanaryWeight(UUID.randomUUID(), tenantId, actor, Instant.now(), routeId, newWeight));
    }

    public void sendAttachFilter(UUID routeId, UUID filterId, int order, String phase,
                                 UUID tenantId, String actor) {
        kafka.publishCommand(KafkaTopics.ROUTE_COMMANDS,
                new CommandEvent.AttachFilter(UUID.randomUUID(), tenantId, actor, Instant.now(),
                        routeId, filterId, order, phase));
    }

    public void sendDetachFilter(UUID routeId, UUID filterId, UUID tenantId, String actor) {
        kafka.publishCommand(KafkaTopics.ROUTE_COMMANDS,
                new CommandEvent.DetachFilter(UUID.randomUUID(), tenantId, actor, Instant.now(),
                        routeId, filterId));
    }

    // ─── Cache Commands (Kafka) ────────────────────────────────────────────────

    public void sendPurgeCacheRoute(UUID routeId, UUID tenantId, String actor) {
        kafka.publishCommand(KafkaTopics.ROUTE_COMMANDS,
                new CommandEvent.PurgeCacheRoute(UUID.randomUUID(), tenantId, actor, Instant.now(), routeId));
    }

    // ─── Circuit Breaker Commands (Kafka) ──────────────────────────────────────

    /**
     * Force a circuit breaker state transition on all gateway instances.
     *
     * @param routeId  the route whose circuit breaker to affect
     * @param tenantId tenant scope
     * @param actor    requesting user
     * @param action   one of "FORCE_OPEN", "FORCE_CLOSED", "RESET"
     */
    public void sendForceCircuitBreaker(UUID routeId, UUID tenantId, String actor, String action) {
        kafka.publishCommand(KafkaTopics.ROUTE_COMMANDS,
                new CommandEvent.ForceCircuitBreaker(UUID.randomUUID(), tenantId, actor, Instant.now(), routeId, action));
    }

    // ─── Filter Commands (Kafka) ──────────────────────────────────────────────

    public void sendCreateFilter(UUID tenantId, String actor, CreateFilterRequest req) {
        FilterType type = resolveFilterType(req.filterType());
        filterConfigValidator.validate(type, req.config());
        kafka.publishCommand(KafkaTopics.FILTER_COMMANDS, new CommandEvent.CreateFilter(
                UUID.randomUUID(), tenantId, actor, Instant.now(),
                req.name(), req.description(), type,
                req.config(), req.gatewayConfigRef()));
    }

    /**
     * Resolves and validates a filter type string against the {@link FilterType} enum.
     * Throws {@link RoutifyException.Validation} for unknown or deprecated types.
     */
    private FilterType resolveFilterType(String filterTypeStr) {
        FilterType type;
        try {
            type = FilterType.valueOf(filterTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new RoutifyException.Validation(
                    "Unknown filter type '%s'. Must be one of the supported FilterType values."
                            .formatted(filterTypeStr));
        }
        if (type.isDeprecated()) {
            throw new RoutifyException.Validation(
                    "Filter type '%s' is deprecated and cannot be used for new filters. Use '%s' instead."
                            .formatted(type.name(), FilterType.suggestedReplacement(type)));
        }
        return type;
    }

    public void sendUpdateFilter(UUID id, UUID tenantId, String actor, UpdateFilterRequest req) {
        kafka.publishCommand(KafkaTopics.FILTER_COMMANDS, new CommandEvent.UpdateFilter(
                UUID.randomUUID(), tenantId, actor, Instant.now(),
                id,
                req.name(), req.description(),
                req.config(), req.gatewayConfigRef()));
    }

    public void sendDeleteFilter(UUID id, UUID tenantId, String actor) {
        kafka.publishCommand(KafkaTopics.FILTER_COMMANDS,
                new CommandEvent.DeleteFilter(UUID.randomUUID(), tenantId, actor, Instant.now(), id));
    }

    // ─── Generic command publishing (used by import/export) ────────────────────

    /**
     * Publish an arbitrary route command to the route commands topic.
     * Used by {@link io.routify.admin.service.ImportService} to dispatch
     * commands with pre-generated idempotent commandIds.
     */
    public void publishRouteCommand(CommandEvent command) {
        kafka.publishCommand(KafkaTopics.ROUTE_COMMANDS, command);
    }

    /**
     * Publish an arbitrary filter command to the filter commands topic.
     * Used by {@link io.routify.admin.service.ImportService} to dispatch
     * commands with pre-generated idempotent commandIds.
     */
    public void publishFilterCommand(CommandEvent command) {
        kafka.publishCommand(KafkaTopics.FILTER_COMMANDS, command);
    }
}
