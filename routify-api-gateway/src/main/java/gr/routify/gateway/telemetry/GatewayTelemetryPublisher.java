package gr.routify.gateway.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.common.client.KafkaServiceClientSupport;
import gr.routify.common.event.KafkaTopics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
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
 * <p>Extends {@link KafkaServiceClientSupport} so all serialisation, partition-key
 * resolution, and delivery logging are handled consistently via the shared
 * {@link #publish(String, String, Object)} method. The {@code TelemetryEvent} record
 * is serialised directly by Jackson — no intermediate {@code toMap()} step — which
 * eliminates the fragile manual conversion that previously caused malformed payloads
 * and drove records to the DLQ.
 */
@Slf4j
@Component
public class GatewayTelemetryPublisher extends KafkaServiceClientSupport {

    public GatewayTelemetryPublisher(KafkaTemplate<String, Object> kafkaTemplate,
                                     ObjectMapper objectMapper) {
        super(kafkaTemplate, objectMapper, "api-gateway");
    }

    /**
     * Publishes a telemetry event for a completed (successful or failed) request.
     *
     * <p>The correlationId is used as the Kafka partition key so all events for the
     * same request land on the same partition, preserving ordering for replay.
     *
     * @param event the telemetry event to publish
     */
    public void publish(TelemetryEvent event) {
        String key = event.correlationId() != null ? event.correlationId() : UUID.randomUUID().toString();
        // Delegates to KafkaServiceClientSupport#publish — handles serialisation,
        // whenComplete logging, and surfaces errors as RoutifyException.GatewayError
        // rather than silently swallowing them (which left records in an inconsistent state).
        publish(KafkaTopics.REQUEST_TELEMETRY, key, event);
    }

    /**
     * Immutable record representing a single request telemetry event.
     *
     * <p>Serialised directly by Jackson. All fields map 1-to-1 to the JSON property
     * names expected by {@code RequestTelemetryConsumer} in routify-audit-service.
     * {@link Instant} fields are rendered as ISO-8601 strings by the shared
     * {@code JavaTimeModule} registered on the gateway's {@link ObjectMapper}.
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
    ) {}

    /**
     * Represents the execution span of a single filter in the chain.
     */
    public record FilterSpan(String filterName, long durationMs, String outcome) {}
}

