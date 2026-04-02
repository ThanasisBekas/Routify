package gr.routify.admin.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.common.client.AmqpServiceClientSupport;
import gr.routify.common.event.QueryRequest;
import gr.routify.common.event.RabbitTopology;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * RabbitMQ client for gateway configuration persistence in routify-route-service.
 *
 * <p>Communicates with routify-route-service via the Direct Reply-To pattern using
 * strongly-typed {@link QueryRequest} records:
 * <ul>
 *   <li><b>GET</b>: {@link QueryRequest.GatewayConfigGet} → routing key {@code gateway.config.get}</li>
 *   <li><b>SAVE</b>: {@link QueryRequest.GatewayConfigSave} → routing key {@code gateway.config.save}</li>
 * </ul>
 *
 * <p>route-service is the durable store — it persists to PostgreSQL and publishes
 * a {@code GatewayConfigChanged} Kafka event via the transactional outbox so all
 * gateway pods reload their config.
 */
@Slf4j
@Component
public class RouteServiceConfigClient extends AmqpServiceClientSupport {

    public RouteServiceConfigClient(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        super(rabbitTemplate, objectMapper, RabbitTopology.EXCHANGE_ROUTE_SERVICE, "admin-api");
    }

    /**
     * Fetches the full gateway configuration from route-service's PostgreSQL store.
     * Returns an empty map if no config has been persisted yet (first-run).
     */
    public Map<String, Object> fetchConfig() {
        try {
            return rpc(RabbitTopology.RK_GATEWAY_CONFIG_GET, new QueryRequest.GatewayConfigGet());
        } catch (Exception e) {
            log.warn("Failed to fetch gateway config from route-service via RabbitMQ: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * Persists the full gateway configuration to route-service's PostgreSQL store.
     * Also triggers {@code GatewayConfigChanged} Kafka event via the transactional outbox.
     *
     * @param configMap  full GatewayConfigDto as a plain Map (Jackson serialized)
     * @param changedBy  actor name for audit trail
     * @param section    human-readable section name for the Kafka event
     */
    public Map<String, Object> saveConfig(Map<String, Object> configMap, String changedBy, String section) {
        try {
            return rpc(RabbitTopology.RK_GATEWAY_CONFIG_SAVE,
                    new QueryRequest.GatewayConfigSave(
                            section  != null ? section   : "full",
                            changedBy != null ? changedBy : "system",
                            configMap));
        } catch (Exception e) {
            log.error("Failed to save gateway config via RabbitMQ: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to save gateway configuration via RabbitMQ", e);
        }
    }
}
