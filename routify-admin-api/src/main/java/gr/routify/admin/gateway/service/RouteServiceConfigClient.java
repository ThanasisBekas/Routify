package gr.routify.admin.gateway.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.common.event.RabbitTopology;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * RabbitMQ client for gateway configuration persistence in routify-route-service.
 *
 * <p>Replaces the previous HTTP WebClient implementation.
 * Communicates with routify-route-service via the Direct Reply-To pattern:
 * <ul>
 *   <li><b>GET</b>: sends to {@code routify.route-service} exchange,
 *       routing key {@code gateway.config.get}</li>
 *   <li><b>SAVE</b>: sends to {@code routify.route-service} exchange,
 *       routing key {@code gateway.config.save} with headers for audit trail</li>
 * </ul>
 *
 * <p>route-service is the durable store — it persists to PostgreSQL and publishes
 * a {@code GatewayConfigChanged} Kafka event via the transactional outbox so all
 * gateway pods reload their config.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouteServiceConfigClient {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper   objectMapper;

    /**
     * Fetches the full gateway configuration from route-service's PostgreSQL store.
     * Returns an empty map if no config has been persisted yet (first-run).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchConfig() {
        try {
            Object response = rabbitTemplate.convertSendAndReceive(
                    RabbitTopology.EXCHANGE_ROUTE_SERVICE,
                    RabbitTopology.RK_GATEWAY_CONFIG_GET,
                    "{}");
            if (response == null) {
                log.warn("route-service returned null for gateway config GET");
                return Map.of();
            }
            return objectMapper.readValue(response.toString(), new TypeReference<>() {});
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
    @SuppressWarnings("unchecked")
    public Map<String, Object> saveConfig(Map<String, Object> configMap, String changedBy, String section) {
        try {
            String body = objectMapper.writeValueAsString(configMap);

            // Build AMQP message with custom headers for audit trail
            MessageProperties props = new MessageProperties();
            props.setHeader(RabbitTopology.HEADER_CHANGED_BY, changedBy != null ? changedBy : "system");
            props.setHeader(RabbitTopology.HEADER_CONFIG_SECTION, section != null ? section : "full");
            props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            Message message = MessageBuilder
                    .withBody(body.getBytes(StandardCharsets.UTF_8))
                    .andProperties(props)
                    .build();

            Message reply = rabbitTemplate.sendAndReceive(
                    RabbitTopology.EXCHANGE_ROUTE_SERVICE,
                    RabbitTopology.RK_GATEWAY_CONFIG_SAVE,
                    message);

            if (reply == null) {
                log.error("route-service returned null for gateway config SAVE — changes may not be persisted");
                return Map.of("error", "no reply from route-service");
            }
            String replyBody = new String(reply.getBody(), StandardCharsets.UTF_8);
            return objectMapper.readValue(replyBody, new TypeReference<>() {});

        } catch (Exception e) {
            log.error("Failed to save gateway config via RabbitMQ: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to save gateway configuration via RabbitMQ", e);
        }
    }
}

