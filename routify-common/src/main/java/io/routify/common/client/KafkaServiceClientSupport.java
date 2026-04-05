package io.routify.common.client;

import io.routify.common.event.CommandEvent;
import io.routify.common.event.DomainEvent;
import io.routify.common.event.KafkaTopics;
import io.routify.common.exception.RoutifyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Base class for all Routify service clients that publish messages to Kafka.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @Component
 * public class RouteFilterMessagingClient extends KafkaServiceClientSupport {
 *
 *     public RouteFilterMessagingClient(KafkaTemplate<String, Object> kafka,
 *                                       ObjectMapper mapper) {
 *         super(kafka, mapper, "route-service");
 *     }
 *
 *     public void sendRouteCommand(String command, Map<String, Object> payload,
 *                                  UUID tenantId, String userId) {
 *         publishCommand(KafkaTopics.ROUTE_COMMANDS, command, payload, tenantId, userId);
 *     }
 * }
 * }</pre>
 *
 * <h2>What this class handles automatically</h2>
 * <ul>
 *   <li>JSON serialisation delegated to the {@link KafkaTemplate}'s value serializer
 *       (must be {@code JsonSerializer} or equivalent — <em>not</em> {@code StringSerializer})</li>
 *   <li>Standard command envelope: {@code {commandId, command, tenantId, requestedBy, payload}}</li>
 *   <li>Partition key resolution: {@code tenantId.toString()} or {@code "global"} when null</li>
 *   <li>Structured log on every publish (info on success, error on failure)</li>
 *   <li>Fire-and-forget ({@link #publish}) vs. confirmed-ACK ({@link #publishSync}) variants</li>
 *   <li>Domain-event shorthand ({@link #publishEvent}) using the event's own {@code tenantId}</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * All methods are thread-safe. {@link #publishSync} blocks the calling thread until the broker
 * ACKs or the timeout elapses. When running with {@code spring.threads.virtual.enabled=true}
 * (the Routify default) this pins a virtual thread — no platform-thread starvation.
 */
@Slf4j
public abstract class KafkaServiceClientSupport {

    /** Default broker-ACK timeout used by {@link #publishSync}. */
    private static final long DEFAULT_SYNC_TIMEOUT_SECONDS = 5L;

    protected final KafkaTemplate<String, Object> kafkaTemplate;

    /** Logical name of the publishing service — appears in log messages. */
    private final String serviceName;

    /**
     * @param kafkaTemplate Spring Kafka template, shared across the application context.
     *                      Must be backed by a value serializer that handles arbitrary
     *                      objects (e.g. {@code JsonSerializer}).
     * @param serviceName   Logical name of the <em>publishing</em> service (e.g. {@code "admin-api"}).
     */
    protected KafkaServiceClientSupport(KafkaTemplate<String, Object> kafkaTemplate,
                                        String serviceName) {
        this.kafkaTemplate = kafkaTemplate;
        this.serviceName   = serviceName;
    }

    // ─── Fire-and-forget ──────────────────────────────────────────────────────

    /**
     * Serialises {@code payload} to JSON and publishes it to {@code topic} with
     * {@code partitionKey} as the Kafka message key.
     *
     * <p>The send is fully asynchronous — this method returns as soon as the record
     * is handed off to the Kafka producer buffer. Delivery failure is logged as a
     * warning but does <em>not</em> throw (use {@link #publishSync} when you need
     * at-least-once guarantees without an outbox).
     *
     * @param topic        Target Kafka topic (see {@link KafkaTopics}).
     * @param partitionKey Message key used for partition assignment and ordering.
     * @param payload      Object to serialise to JSON. Must be Jackson-serialisable.
     */
    public final void publish(String topic, String partitionKey, Object payload) {
        try {
            kafkaTemplate.send(topic, partitionKey, payload)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.warn("[{}] Async publish failed — topic={} key={}: {}",
                                    serviceName, topic, partitionKey, ex.getMessage());
                        } else {
                            log.debug("[{}] Published — topic={} key={} offset={}",
                                    serviceName, topic, partitionKey,
                                    result.getRecordMetadata().offset());
                        }
                    });
        } catch (Exception e) {
            log.error("[{}] publish error — topic={} key={}: {}",
                    serviceName, topic, partitionKey, e.getMessage(), e);
            throw new RoutifyException.GatewayError(
                    "Failed to publish to topic %s: %s".formatted(topic, e.getMessage()), e);
        }
    }

    // ─── Synchronous (confirmed-ACK) publish ─────────────────────────────────

    /**
     * Serialises {@code payload} to JSON and publishes it to {@code topic}, blocking
     * until the broker acknowledges the record or {@value #DEFAULT_SYNC_TIMEOUT_SECONDS}
     * seconds elapse.
     *
     * <p>Intended for use cases where you need delivery confirmation but do not have
     * an outbox (e.g. manual control-plane triggers, test utilities). For high-throughput
     * paths prefer an outbox + {@link #publish}.
     *
     * @param topic        Target Kafka topic.
     * @param partitionKey Message key.
     * @param payload      Object to serialise to JSON.
     * @throws RoutifyException.GatewayError if the broker does not ACK within the timeout
     *                                        or any other send error occurs.
     */
    public final void publishSync(String topic, String partitionKey, Object payload) {
        publishSync(topic, partitionKey, payload, DEFAULT_SYNC_TIMEOUT_SECONDS);
    }

    /**
     * Same as {@link #publishSync(String, String, Object)} but with a custom timeout.
     */
    public final void publishSync(String topic, String partitionKey, Object payload,
                                     long timeoutSeconds) {
        try {
            kafkaTemplate.send(topic, partitionKey, payload)
                    .get(timeoutSeconds, TimeUnit.SECONDS);
            log.info("[{}] Sync-published — topic={} key={}", serviceName, topic, partitionKey);
        } catch (TimeoutException e) {
            log.error("[{}] publishSync timed out after {}s — topic={} key={}",
                    serviceName, timeoutSeconds, topic, partitionKey);
            throw new RoutifyException.GatewayError(
                    "Kafka publish timed out after %ds (topic=%s)".formatted(timeoutSeconds, topic), e);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("[{}] publishSync failed — topic={} key={}: {}",
                    serviceName, topic, partitionKey, cause.getMessage(), cause);
            throw new RoutifyException.GatewayError(
                    "Failed to publish to topic %s: %s".formatted(topic, cause.getMessage()), cause);
        }
    }

    // ─── Command envelope shorthand ───────────────────────────────────────────

    /**
     * Builds the standard Routify command envelope and publishes it to {@code topic}.
     *
     * <p>Envelope structure:
     * <pre>{@code
     * {
     *   "commandId"  : "<random UUID>",
     *   "command"    : "<command>",
     *   "tenantId"   : "<tenantId>" | null,
     *   "requestedBy": "<actor>",
     *   "payload"    : { ... }
     * }
     * }</pre>
     *
     * <p>The partition key is {@code tenantId.toString()} when non-null, otherwise
     * {@code "global"} so all tenant-agnostic commands land on the same partition.
     *
     * @param topic       Target command topic (see {@link KafkaTopics}).
     * @param command     Command name (e.g. {@code "CREATE_ROUTE"}).
     * @param payload     Command-specific data map.
     * @param tenantId    Tenant scope; may be {@code null} for system-wide commands.
     * @param requestedBy Actor who initiated the command (user ID or service name).
     */
    public final void publishCommand(String topic,
                                        String command,
                                        Map<String, Object> payload,
                                        UUID tenantId,
                                        String requestedBy) {
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("commandId",    UUID.randomUUID().toString());
        envelope.put("command",      command);
        envelope.put("tenantId",     tenantId != null ? tenantId.toString() : null);
        envelope.put("requestedBy",  requestedBy);
        envelope.put("payload",      payload);

        String partitionKey = tenantId != null ? tenantId.toString() : "global";

        log.info("[{}] Publishing command={} to topic={} tenantId={} by={}",
                serviceName, command, topic, tenantId, requestedBy);
        publish(topic, partitionKey, envelope);
    }

    /**
     * Typed overload — serialises a strongly-typed {@link CommandEvent} and publishes
     * it to {@code topic}.
     *
     * <p>The partition key is {@code command.tenantId().toString()} or {@code "global"}
     * for system-wide commands. The {@code "type"} discriminator is embedded by Jackson's
     * {@link com.fasterxml.jackson.annotation.JsonTypeInfo} so consumers can deserialise
     * back to the concrete record with a single {@code objectMapper.readValue()} call.
     *
     * @param topic   Target command topic (see {@link KafkaTopics}).
     * @param command Strongly-typed command record.
     */
    public final void publishCommand(String topic, CommandEvent command) {
        String partitionKey = command.tenantId() != null
                ? command.tenantId().toString()
                : "global";
        log.info("[{}] Publishing command={} to topic={} tenantId={} by={}",
                serviceName, command.getClass().getSimpleName(), topic,
                command.tenantId(), command.requestedBy());
        publish(topic, partitionKey, command);
    }

    // ─── Domain event shorthand ───────────────────────────────────────────────

    /**
     * Serialises a {@link DomainEvent} and publishes it to {@code topic}.
     *
     * <p>The partition key is the event's {@code tenantId} (converted to string), or
     * {@code "system"} when the event is tenant-agnostic (e.g.
     * {@link DomainEvent.GatewayConfigChanged} with a {@code null} tenantId).
     *
     * @param topic Kafka topic to publish to.
     * @param event Domain event to publish.
     */
    public final void publishEvent(String topic, DomainEvent event) {
        String partitionKey = event.tenantId() != null
                ? event.tenantId().toString()
                : "system";
        log.info("[{}] Publishing event={} to topic={} tenantId={}",
                serviceName, event.getClass().getSimpleName(), topic, event.tenantId());
        publish(topic, partitionKey, event);
    }

    // ─── Accessor ─────────────────────────────────────────────────────────────

    /** Returns the logical service name of this client. */
    protected String serviceName() { return serviceName; }
}
