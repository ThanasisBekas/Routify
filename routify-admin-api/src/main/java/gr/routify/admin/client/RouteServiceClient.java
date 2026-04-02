package gr.routify.admin.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.common.client.AmqpServiceClientSupport;
import gr.routify.common.event.QueryRequest;
import gr.routify.common.event.RabbitTopology;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * RabbitMQ client for miscellaneous routify-route-service and routify-api-gateway calls
 * that are not covered by {@link RouteFilterMessagingClient} (stats, gateway status).
 *
 * <p>Uses the RabbitMQ Direct Reply-To pattern via {@link AmqpServiceClientSupport}.
 */
@Slf4j
@Component
public class RouteServiceClient extends AmqpServiceClientSupport {

    public RouteServiceClient(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        super(rabbitTemplate, objectMapper, RabbitTopology.EXCHANGE_ROUTE_SERVICE, "admin-api");
    }

    /**
     * Fetches route statistics (counts by status) for a given tenant.
     */
    @CircuitBreaker(name = "route-service", fallbackMethod = "getRouteStatsFallback")
    public Map<String, Object> getRouteStats(UUID tenantId) {
        try {
            return rpc(RabbitTopology.RK_ROUTE_STATS, new QueryRequest.RouteStats(tenantId));
        } catch (Exception e) {
            log.error("Failed to fetch route stats via RabbitMQ: {}", e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private Map<String, Object> getRouteStatsFallback(UUID tenantId, Throwable t) {
        log.warn("getRouteStats circuit open or timed out: {}", t.getMessage());
        return Map.of("error", "route-service temporarily unavailable", "circuitOpen", true);
    }

    /**
     * Fetches gateway status from routify-api-gateway via RabbitMQ.
     * Note: sends to the gateway exchange, not the route-service exchange.
     */
    @CircuitBreaker(name = "route-service", fallbackMethod = "getGatewayStatusFallback")
    public Map<String, Object> getGatewayStatus() {
        try {
            // The gateway lives on its own exchange — call rabbitTemplate directly
            var req = objectMapper.writeValueAsString(new QueryRequest.GatewaySnapshot());
            var msg = org.springframework.amqp.core.MessageBuilder
                    .withBody(req.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .andProperties(buildGatewayProps())
                    .build();
            var reply = rabbitTemplate.sendAndReceive(
                    RabbitTopology.EXCHANGE_GATEWAY, RabbitTopology.RK_GATEWAY_STATUS_REQUEST, msg);
            if (reply == null) return Map.of("status", "DOWN", "error", "gateway unavailable");
            return objectMapper.readValue(
                    new String(reply.getBody(), java.nio.charset.StandardCharsets.UTF_8),
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to fetch gateway status via RabbitMQ: {}", e.getMessage());
            return Map.of("status", "UNKNOWN", "error", e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private Map<String, Object> getGatewayStatusFallback(Throwable t) {
        log.warn("getGatewayStatus circuit open or timed out: {}", t.getMessage());
        return Map.of("status", "UNKNOWN", "error", "gateway temporarily unavailable", "circuitOpen", true);
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private org.springframework.amqp.core.MessageProperties buildGatewayProps() {
        var props = new org.springframework.amqp.core.MessageProperties();
        props.setContentType(org.springframework.amqp.core.MessageProperties.CONTENT_TYPE_JSON);
        props.setHeader(RabbitTopology.HEADER_FROM_SERVICE, serviceName());
        return props;
    }
}

