package gr.routify.admin.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.common.client.AmqpServiceClientSupport;
import gr.routify.common.client.KafkaServiceClientSupport;
import gr.routify.common.domain.FilterType;
import gr.routify.common.event.CommandEvent;
import gr.routify.common.event.KafkaTopics;
import gr.routify.common.event.QueryRequest;
import gr.routify.common.event.RabbitTopology;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
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

    public RouteFilterMessagingClient(RabbitTemplate rabbitTemplate,
                                      ObjectMapper objectMapper,
                                      KafkaTemplate<String, String> kafkaTemplate) {
        super(rabbitTemplate, objectMapper, RabbitTopology.EXCHANGE_ROUTE_SERVICE, "admin-api");
        this.kafka = new KafkaServiceClientSupport(kafkaTemplate, objectMapper, "admin-api") {};
    }

    // ─── Route Queries (RabbitMQ) ─────────────────────────────────────────────

    @CircuitBreaker(name = "route-service", fallbackMethod = "queryRoutesFallback")
    public Map<String, Object> queryRoutes(UUID tenantId, String status, int page, int size,
                                           String sortBy, String sortDir) {
        try {
            return rpc(RabbitTopology.RK_ROUTES_QUERY,
                    new QueryRequest.RoutesQuery(tenantId, status, page, size, sortBy, sortDir));
        } catch (Exception e) {
            log.error("queryRoutes failed: {}", e.getMessage(), e);
            return Map.of("error", e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private Map<String, Object> queryRoutesFallback(UUID tenantId, String status, int page, int size,
                                                     String sortBy, String sortDir, Throwable t) {
        log.warn("queryRoutes circuit open or timed out: {}", t.getMessage());
        return Map.of("error", "route-service temporarily unavailable", "circuitOpen", true);
    }

    @CircuitBreaker(name = "route-service", fallbackMethod = "getRouteFallback")
    public Map<String, Object> getRoute(UUID id, UUID tenantId) {
        try {
            return rpc(RabbitTopology.RK_ROUTES_GET, new QueryRequest.RouteGet(id, tenantId));
        } catch (Exception e) {
            log.error("getRoute failed: {}", e.getMessage(), e);
            return Map.of("error", e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private Map<String, Object> getRouteFallback(UUID id, UUID tenantId, Throwable t) {
        log.warn("getRoute circuit open or timed out: {}", t.getMessage());
        return Map.of("error", "route-service temporarily unavailable", "circuitOpen", true);
    }

    @CircuitBreaker(name = "route-service", fallbackMethod = "cloneRouteFallback")
    public Map<String, Object> cloneRoute(UUID sourceId, UUID tenantId, String userId) {
        try {
            return rpc(RabbitTopology.RK_ROUTES_CLONE,
                    new QueryRequest.RouteClone(sourceId, tenantId, userId));
        } catch (Exception e) {
            log.error("cloneRoute failed: {}", e.getMessage(), e);
            return Map.of("error", e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private Map<String, Object> cloneRouteFallback(UUID sourceId, UUID tenantId, String userId, Throwable t) {
        log.warn("cloneRoute circuit open or timed out: {}", t.getMessage());
        return Map.of("error", "route-service temporarily unavailable", "circuitOpen", true);
    }

    // ─── Filter Queries (RabbitMQ) ────────────────────────────────────────────

    @CircuitBreaker(name = "route-service", fallbackMethod = "queryFiltersFallback")
    public Map<String, Object> queryFilters(UUID tenantId, int page, int size,
                                            String sortBy, String sortDir) {
        try {
            return rpc(RabbitTopology.RK_FILTERS_QUERY,
                    new QueryRequest.FiltersQuery(tenantId, page, size, sortBy, sortDir));
        } catch (Exception e) {
            log.error("queryFilters failed: {}", e.getMessage(), e);
            return Map.of("error", e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private Map<String, Object> queryFiltersFallback(UUID tenantId, int page, int size,
                                                      String sortBy, String sortDir, Throwable t) {
        log.warn("queryFilters circuit open or timed out: {}", t.getMessage());
        return Map.of("error", "route-service temporarily unavailable", "circuitOpen", true);
    }

    @CircuitBreaker(name = "route-service", fallbackMethod = "getFilterFallback")
    public Map<String, Object> getFilter(UUID id, UUID tenantId) {
        try {
            return rpc(RabbitTopology.RK_FILTERS_GET, new QueryRequest.FilterGet(id, tenantId));
        } catch (Exception e) {
            log.error("getFilter failed: {}", e.getMessage(), e);
            return Map.of("error", e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private Map<String, Object> getFilterFallback(UUID id, UUID tenantId, Throwable t) {
        log.warn("getFilter circuit open or timed out: {}", t.getMessage());
        return Map.of("error", "route-service temporarily unavailable", "circuitOpen", true);
    }

    // ─── Route Commands (Kafka) ───────────────────────────────────────────────

    public void sendCreateRoute(UUID tenantId, String actor, Map<String, Object> req) {
        kafka.publishCommand(KafkaTopics.ROUTE_COMMANDS, new CommandEvent.CreateRoute(
                UUID.randomUUID(), tenantId, actor, Instant.now(),
                str(req, "name"), str(req, "description"), str(req, "pathPattern"),
                str(req, "methods"), str(req, "upstreamUri"), str(req, "stripPrefix"),
                map(req, "extraConfig")));
    }

    public void sendUpdateRoute(UUID id, UUID tenantId, String actor, Map<String, Object> req) {
        kafka.publishCommand(KafkaTopics.ROUTE_COMMANDS, new CommandEvent.UpdateRoute(
                UUID.randomUUID(), tenantId, actor, Instant.now(),
                id,
                str(req, "name"), str(req, "description"), str(req, "pathPattern"),
                str(req, "methods"), str(req, "upstreamUri"), str(req, "stripPrefix"),
                map(req, "extraConfig")));
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

    // ─── Filter Commands (Kafka) ──────────────────────────────────────────────

    public void sendCreateFilter(UUID tenantId, String actor, Map<String, Object> req) {
        FilterType type = req.get("filterType") != null
                ? FilterType.valueOf(req.get("filterType").toString().toUpperCase())
                : FilterType.CUSTOM_SPEL;
        kafka.publishCommand(KafkaTopics.FILTER_COMMANDS, new CommandEvent.CreateFilter(
                UUID.randomUUID(), tenantId, actor, Instant.now(),
                str(req, "name"), str(req, "description"), type,
                map(req, "config"), map(req, "gatewayConfigRef")));
    }

    public void sendUpdateFilter(UUID id, UUID tenantId, String actor, Map<String, Object> req) {
        kafka.publishCommand(KafkaTopics.FILTER_COMMANDS, new CommandEvent.UpdateFilter(
                UUID.randomUUID(), tenantId, actor, Instant.now(),
                id,
                str(req, "name"), str(req, "description"),
                map(req, "config"), map(req, "gatewayConfigRef")));
    }

    public void sendDeleteFilter(UUID id, UUID tenantId, String actor) {
        kafka.publishCommand(KafkaTopics.FILTER_COMMANDS,
                new CommandEvent.DeleteFilter(UUID.randomUUID(), tenantId, actor, Instant.now(), id));
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }
}


