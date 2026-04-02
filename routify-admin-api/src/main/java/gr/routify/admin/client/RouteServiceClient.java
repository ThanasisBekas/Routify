package gr.routify.admin.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.common.event.RabbitTopology;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * RabbitMQ client for calling routify-route-service internal APIs.
 *
 * <p>Replaces the previous HTTP WebClient implementation. Uses the RabbitMQ
 * Direct Reply-To pattern: sends to {@code routify.route-service} exchange,
 * route-service handles and replies synchronously.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouteServiceClient {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper   objectMapper;

    /**
     * Fetches route statistics (counts by status) for a given tenant.
     */
    @SuppressWarnings("unchecked")
    @CircuitBreaker(name = "route-service", fallbackMethod = "getRouteStatsFallback")
    public Map<String, Object> getRouteStats(UUID tenantId) {
        try {
            String request = tenantId != null
                    ? "{\"tenantId\":\"" + tenantId + "\"}"
                    : "{}";
            Object response = rabbitTemplate.convertSendAndReceive(
                    RabbitTopology.EXCHANGE_ROUTE_SERVICE,
                    RabbitTopology.RK_ROUTE_STATS,
                    request);
            if (response == null) return Map.of("error", "route-service unavailable");
            return objectMapper.readValue(toJson(response), new TypeReference<>() {});
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
     */
    @SuppressWarnings("unchecked")
    @CircuitBreaker(name = "route-service", fallbackMethod = "getGatewayStatusFallback")
    public Map<String, Object> getGatewayStatus() {
        try {
            Object response = rabbitTemplate.convertSendAndReceive(
                    RabbitTopology.EXCHANGE_GATEWAY,
                    RabbitTopology.RK_GATEWAY_STATUS_REQUEST,
                    "{}");
            if (response == null) return Map.of("status", "DOWN", "error", "gateway unavailable");
            return objectMapper.readValue(toJson(response), new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to fetch gateway status via RabbitMQ: {}", e.getMessage());
            return Map.of("status", "UNKNOWN", "error", e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private Map<String, Object> getGatewayStatusFallback(Throwable t) {
        log.warn("getGatewayStatus circuit open or timed out: {}", t.getMessage());
        return Map.of("status", "UNKNOWN", "error", "route-service temporarily unavailable",
                "circuitOpen", true);
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private String toJson(Object response) throws Exception {
        return switch (response) {
            case String s -> s;
            case byte[] b -> new String(b, java.nio.charset.StandardCharsets.UTF_8);
            default       -> objectMapper.writeValueAsString(response);
        };
    }
}

