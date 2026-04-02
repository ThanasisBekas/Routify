package gr.routify.admin.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.common.event.KafkaTopics;
import gr.routify.common.event.RabbitTopology;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
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
@RequiredArgsConstructor
public class RouteFilterMessagingClient {

    private final RabbitTemplate              rabbitTemplate;
    private final KafkaTemplate<String,String> kafkaTemplate;
    private final ObjectMapper                objectMapper;

    // ─── Route Queries (RabbitMQ) ─────────────────────────────────────────────

    @CircuitBreaker(name = "route-service", fallbackMethod = "queryRoutesFallback")
    public Map<String, Object> queryRoutes(UUID tenantId, String status, int page, int size,
                                           String sortBy, String sortDir) {
        try {
            var req = Map.of(
                    "tenantId", tenantId != null ? tenantId.toString() : "",
                    "status",   status != null ? status : "",
                    "page",     page, "size", size,
                    "sortBy",   sortBy != null ? sortBy : "createdAt",
                    "sortDir",  sortDir != null ? sortDir : "DESC"
            );
            return rpcRouteService(RabbitTopology.RK_ROUTES_QUERY, req);
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
            var req = Map.of("id", id.toString(), "tenantId", tenantId.toString());
            return rpcRouteService(RabbitTopology.RK_ROUTES_GET, req);
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
            var req = new java.util.LinkedHashMap<String, Object>();
            req.put("id", sourceId.toString());
            req.put("tenantId", tenantId.toString());
            req.put("requestedBy", userId);
            return rpcRouteService(RabbitTopology.RK_ROUTES_CLONE, req);
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
            var req = Map.of(
                    "tenantId", tenantId != null ? tenantId.toString() : "",
                    "page", page, "size", size,
                    "sortBy",  sortBy  != null ? sortBy  : "createdAt",
                    "sortDir", sortDir != null ? sortDir : "DESC"
            );
            return rpcRouteService(RabbitTopology.RK_FILTERS_QUERY, req);
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
            var req = Map.of("id", id.toString(), "tenantId", tenantId.toString());
            return rpcRouteService(RabbitTopology.RK_FILTERS_GET, req);
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

    public void sendRouteCommand(String command, Map<String, Object> payload, UUID tenantId, String userId) {
        try {
            var envelope = new java.util.LinkedHashMap<String, Object>();
            envelope.put("command",   command);
            envelope.put("tenantId",  tenantId != null ? tenantId.toString() : null);
            envelope.put("requestedBy", userId);
            envelope.put("payload",   payload);
            envelope.put("commandId", UUID.randomUUID().toString());
            kafkaTemplate.send(KafkaTopics.ROUTE_COMMANDS, tenantId != null ? tenantId.toString() : "global",
                    objectMapper.writeValueAsString(envelope));
            log.info("Route command published: command={} tenantId={} by={}", command, tenantId, userId);
        } catch (Exception e) {
            log.error("Failed to publish route command {}: {}", command, e.getMessage(), e);
            throw new RuntimeException("Failed to publish route command: " + command, e);
        }
    }

    // ─── Filter Commands (Kafka) ──────────────────────────────────────────────

    public void sendFilterCommand(String command, Map<String, Object> payload, UUID tenantId, String userId) {
        try {
            var envelope = new java.util.LinkedHashMap<String, Object>();
            envelope.put("command",     command);
            envelope.put("tenantId",    tenantId != null ? tenantId.toString() : null);
            envelope.put("requestedBy", userId);
            envelope.put("payload",     payload);
            envelope.put("commandId",   UUID.randomUUID().toString());
            kafkaTemplate.send(KafkaTopics.FILTER_COMMANDS, tenantId != null ? tenantId.toString() : "global",
                    objectMapper.writeValueAsString(envelope));
            log.info("Filter command published: command={} tenantId={} by={}", command, tenantId, userId);
        } catch (Exception e) {
            log.error("Failed to publish filter command {}: {}", command, e.getMessage(), e);
            throw new RuntimeException("Failed to publish filter command: " + command, e);
        }
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> rpcRouteService(String routingKey, Object requestBody) throws Exception {
        String body = objectMapper.writeValueAsString(requestBody);
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        Message msg = MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                .andProperties(props).build();
        Message reply = rabbitTemplate.sendAndReceive(
                RabbitTopology.EXCHANGE_ROUTE_SERVICE, routingKey, msg);
        if (reply == null) return Map.of("error", "route-service unavailable");
        return objectMapper.readValue(
                new String(reply.getBody(), StandardCharsets.UTF_8), new TypeReference<>() {});
    }
}

