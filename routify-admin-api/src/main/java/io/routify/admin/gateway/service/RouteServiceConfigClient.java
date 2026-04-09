package io.routify.admin.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.routify.common.client.AmqpServiceClientSupport;
import io.routify.common.event.QueryRequest;
import io.routify.common.event.QueryResponse;
import io.routify.common.event.RabbitTopology;
import io.routify.common.exception.RoutifyException;
import io.routify.common.observability.RoutifyMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

// ...existing code...
@Slf4j
@Component
public class RouteServiceConfigClient extends AmqpServiceClientSupport {

    public RouteServiceConfigClient(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper, RoutifyMetrics metrics) {
        super(rabbitTemplate, objectMapper, RabbitTopology.EXCHANGE_ROUTE_SERVICE, "admin-api", metrics);
    }

    public Map<String, Object> fetchConfig() {
        try {
            QueryResponse.GatewayConfig result = rpc(
                    RabbitTopology.RK_GATEWAY_CONFIG_GET,
                    new QueryRequest.GatewayConfigGet(),
                    QueryResponse.GatewayConfig.class);
            return result != null && result.config() != null ? result.config() : Map.of();
        } catch (Exception e) {
            log.warn("Failed to fetch gateway config from route-service via RabbitMQ: {}", e.getMessage());
            return Map.of();
        }
    }

    public Map<String, Object> saveConfig(Map<String, Object> configMap, String changedBy, String section) {
        try {
            QueryResponse.GatewayConfig result = rpc(
                    RabbitTopology.RK_GATEWAY_CONFIG_SAVE,
                    new QueryRequest.GatewayConfigSave(
                            section   != null ? section   : "full",
                            changedBy != null ? changedBy : "system",
                            configMap),
                    QueryResponse.GatewayConfig.class);
            return result != null && result.config() != null ? result.config() : Map.of();
        } catch (Exception e) {
            log.error("Failed to save gateway config via RabbitMQ: {}", e.getMessage(), e);
            throw new RoutifyException.GatewayError("Failed to save gateway configuration via RabbitMQ", e);
        }
    }
}
