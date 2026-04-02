package gr.routify.gateway.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.common.event.KafkaTopics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes per-request telemetry events to the {@code routify.request.telemetry} Kafka topic.
 *
 * <p>Every request that passes through the gateway — whether it succeeds, fails with a
 * 4xx/5xx status, or throws an exception — is emitted as a telemetry event. This provides
 * a complete, append-only record of all traffic for auditing, analytics, SLA monitoring,
 * and replay.
 *
 * <p>Phase 4.10 fix: uses the Spring-managed {@link ObjectMapper} bean (injected via
 * constructor) instead of a static instance. This ensures consistency with the gateway's
 * configured serialisation settings (custom serialisers, naming strategies, etc.).
 */
@Slf4j
@Component
public class GatewayTelemetryPublisher {

    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public GatewayTelemetryPublisher(KafkaTemplate<String, Object> kafkaTemplate,
                                      ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Publishes a telemetry event for a completed (successful or failed) request.
     *
     * @param event the telemetry event to publish
     */
    public void publish(TelemetryEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event.toMap());
            // Use correlationId as partition key so all events for same request go to same partition
            String key = event.correlationId() != null ? event.correlationId() : UUID.randomUUID().toString();
            kafkaTemplate.send(KafkaTopics.REQUEST_TELEMETRY, key, json)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.warn("Failed to publish telemetry for correlationId={}: {}",
                                    event.correlationId(), ex.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.warn("Failed to serialize telemetry event: {}", e.getMessage());
        }
    }

    /**
     * Immutable record representing a single request telemetry event.
     */
    public record TelemetryEvent(
            String correlationId,
            String tenantId,
            String routeId,
            String routeName,
            String method,
            String path,
            String queryString,
            String upstreamUri,
            String clientIp,
            String userId,
            Integer responseStatus,
            Long durationMs,
            Long requestSizeBytes,
            Long responseSizeBytes,
            String errorMessage,
            boolean failed,
            Instant requestedAt,
            List<FilterSpan> filterTrace,
            Map<String, String> requestHeaders,
            Map<String, String> responseHeaders,
            String requestBody,
            String responseBody
    ) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new HashMap<>();
            m.put("correlationId",      correlationId);
            m.put("tenantId",           tenantId);
            m.put("routeId",            routeId);
            m.put("routeName",          routeName);
            m.put("method",             method);
            m.put("path",               path);
            m.put("queryString",        queryString);
            m.put("upstreamUri",        upstreamUri);
            m.put("clientIp",           clientIp);
            m.put("userId",             userId);
            m.put("responseStatus",     responseStatus);
            m.put("durationMs",         durationMs);
            m.put("requestSizeBytes",   requestSizeBytes);
            m.put("responseSizeBytes",  responseSizeBytes);
            m.put("errorMessage",       errorMessage);
            m.put("failed",             failed);
            m.put("requestedAt",        requestedAt != null ? requestedAt.toString() : null);
            m.put("filterTrace",        filterTrace);
            m.put("requestHeaders",     requestHeaders);
            m.put("responseHeaders",    responseHeaders);
            m.put("requestBody",        requestBody);
            m.put("responseBody",       responseBody);
            return m;
        }
    }

    /**
     * Represents the execution span of a single filter in the chain.
     */
    public record FilterSpan(String filterName, long durationMs, String outcome) {}
}

