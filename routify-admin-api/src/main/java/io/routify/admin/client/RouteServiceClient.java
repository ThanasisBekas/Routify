package io.routify.admin.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.routify.common.client.AmqpServiceClientSupport;
import io.routify.common.event.QueryRequest;
import io.routify.common.event.QueryResponse;
import io.routify.common.event.RabbitTopology;
import io.routify.common.observability.RoutifyMetrics;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * RabbitMQ client for miscellaneous routify-route-service and routify-api-gateway calls
 * that are not covered by {@link RouteFilterMessagingClient} (stats, gateway status).
 */
@Slf4j
@Component
public class RouteServiceClient extends AmqpServiceClientSupport {

    public RouteServiceClient(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper, RoutifyMetrics metrics) {
        super(rabbitTemplate, objectMapper, RabbitTopology.EXCHANGE_ROUTE_SERVICE, "admin-api", metrics);
    }

    @CircuitBreaker(name = "route-service", fallbackMethod = "getRouteStatsFallback")
    public QueryResponse.RouteStatsResult getRouteStats(UUID tenantId) {
        try {
            return rpc(RabbitTopology.RK_ROUTE_STATS,
                    new QueryRequest.RouteStats(tenantId),
                    QueryResponse.RouteStatsResult.class);
        } catch (Exception e) {
            log.error("Failed to fetch route stats via RabbitMQ: {}", e.getMessage());
            throw e;
        }
    }

    @SuppressWarnings("unused")
    private QueryResponse.RouteStatsResult getRouteStatsFallback(UUID tenantId, Throwable t) {
        log.warn("getRouteStats circuit open or timed out: {}", t.getMessage());
        return new QueryResponse.RouteStatsResult(0L, 0L, 0L, 0L, tenantId);
    }

    @CircuitBreaker(name = "route-service", fallbackMethod = "getGatewayStatusFallback")
    public QueryResponse.GatewayStatus getGatewayStatus() {
        try {
            var req = objectMapper.writeValueAsString(new QueryRequest.GatewaySnapshot());
            var msg = org.springframework.amqp.core.MessageBuilder
                    .withBody(req.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .andProperties(buildGatewayProps())
                    .build();
            var reply = rabbitTemplate.sendAndReceive(
                    RabbitTopology.EXCHANGE_GATEWAY, RabbitTopology.RK_GATEWAY_STATUS_REQUEST, msg);
            if (reply == null) {
                return new QueryResponse.GatewayStatus("DOWN", 0, null, null, null);
            }
            return objectMapper.readValue(
                    new String(reply.getBody(), java.nio.charset.StandardCharsets.UTF_8),
                    QueryResponse.GatewayStatus.class);
        } catch (Exception e) {
            log.warn("Failed to fetch gateway status via RabbitMQ: {}", e.getMessage());
            return new QueryResponse.GatewayStatus("UNKNOWN", 0, null, null, null);
        }
    }

    @SuppressWarnings("unused")
    private QueryResponse.GatewayStatus getGatewayStatusFallback(Throwable t) {
        log.warn("getGatewayStatus circuit open or timed out: {}", t.getMessage());
        return new QueryResponse.GatewayStatus("UNKNOWN", 0, null, null, null);
    }

    // ─── Route SLO (Gateway Health Dashboard v2) ──────────────────────────────

    @CircuitBreaker(name = "route-service", fallbackMethod = "getRouteSloFallback")
    public QueryResponse.RouteSloResult getRouteSlo(UUID routeId, UUID tenantId) {
        try {
            return rpc(RabbitTopology.RK_ROUTE_SLO_GET,
                    new QueryRequest.RouteSloGet(routeId, tenantId),
                    QueryResponse.RouteSloResult.class);
        } catch (Exception e) {
            log.error("getRouteSlo failed: {}", e.getMessage());
            throw e;
        }
    }

    @SuppressWarnings("unused")
    private QueryResponse.RouteSloResult getRouteSloFallback(UUID routeId, UUID tenantId, Throwable t) {
        log.warn("getRouteSlo circuit open or timed out: {}", t.getMessage());
        return new QueryResponse.RouteSloResult(routeId, 99.9, 1000, 168, false);
    }

    @CircuitBreaker(name = "route-service", fallbackMethod = "saveRouteSloFallback")
    public QueryResponse.RouteSloResult saveRouteSlo(UUID routeId, UUID tenantId,
                                                      double availabilityTarget,
                                                      int latencyP99TargetMs,
                                                      int evaluationWindowHours) {
        try {
            return rpc(RabbitTopology.RK_ROUTE_SLO_SAVE,
                    new QueryRequest.RouteSloSave(routeId, tenantId,
                            availabilityTarget, latencyP99TargetMs, evaluationWindowHours),
                    QueryResponse.RouteSloResult.class);
        } catch (Exception e) {
            log.error("saveRouteSlo failed: {}", e.getMessage());
            throw e;
        }
    }

    @SuppressWarnings("unused")
    private QueryResponse.RouteSloResult saveRouteSloFallback(UUID routeId, UUID tenantId,
                                                               double availabilityTarget,
                                                               int latencyP99TargetMs,
                                                               int evaluationWindowHours,
                                                               Throwable t) {
        log.warn("saveRouteSlo circuit open or timed out: {}", t.getMessage());
        return new QueryResponse.RouteSloResult(routeId, availabilityTarget,
                latencyP99TargetMs, evaluationWindowHours, false);
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private org.springframework.amqp.core.MessageProperties buildGatewayProps() {
        var props = new org.springframework.amqp.core.MessageProperties();
        props.setContentType(org.springframework.amqp.core.MessageProperties.CONTENT_TYPE_JSON);
        props.setHeader(RabbitTopology.HEADER_FROM_SERVICE, serviceName());
        return props;
    }
}
