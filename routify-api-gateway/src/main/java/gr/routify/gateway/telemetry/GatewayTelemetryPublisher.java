package gr.routify.gateway.telemetry;

import gr.routify.common.client.KafkaServiceClientSupport;
import gr.routify.common.event.KafkaTopics;
import gr.routify.common.event.RequestTelemetryEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Publishes per-request telemetry events to the {@code routify.request.telemetry} Kafka topic.
 *
 * <p>Every request that passes through the gateway — whether it succeeds, fails with a
 * 4xx/5xx status, or throws an exception — is emitted as a telemetry event. This provides
 * a complete, append-only record of all traffic for auditing, analytics, SLA monitoring,
 * and replay.
 *
 * <p>Uses the shared {@link RequestTelemetryEvent} type from {@code routify-common} for
 * type-safe serialization, ensuring that the consumer (routify-audit-service) accesses
 * all fields directly without raw JSON parsing.
 */
@Slf4j
@Component
public class GatewayTelemetryPublisher extends KafkaServiceClientSupport {

    public GatewayTelemetryPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        super(kafkaTemplate, "api-gateway");
    }

    /**
     * Publishes a telemetry event for a completed (successful or failed) request.
     *
     * <p>The correlationId is used as the Kafka partition key so all events for the
     * same request land on the same partition, preserving ordering for replay.
     *
     * @param event the telemetry event to publish
     */
    public void publish(RequestTelemetryEvent event) {
        String key = event.correlationId() != null ? event.correlationId() : UUID.randomUUID().toString();
        publish(KafkaTopics.REQUEST_TELEMETRY, key, event);
    }
}
