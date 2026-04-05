package io.routify.common.kafka;

import io.routify.common.security.SecurityContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.UUID;

/**
 * Factory for the standard Routify Kafka Dead-Letter Queue error handler.
 *
 * <h2>Retry policy</h2>
 * <ul>
 *   <li>Initial interval : 1 second</li>
 *   <li>Multiplier       : 2.0 (exponential)</li>
 *   <li>Max elapsed time : 30 seconds (≈ 5 retries)</li>
 * </ul>
 * After the back-off budget is exhausted the record is forwarded to
 * {@code <original-topic>.DLQ} on the same partition so ordering is
 * preserved within a partition for post-mortem analysis.
 *
 * <h2>Non-retryable exceptions</h2>
 * Deserialization errors ({@code JsonParseException},
 * {@code InvalidDefinitionException}) go straight to the DLQ on the first
 * attempt because retrying them is pointless — the payload is permanently
 * malformed.
 *
 * <h2>DLQ topic naming</h2>
 * {@code <topic-name>.DLQ} — e.g. {@code routify.route.events.DLQ}.
 * Enable {@code KAFKA_AUTO_CREATE_TOPICS_ENABLE=true} in dev, or create
 * these topics manually in production.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * factory.setCommonErrorHandler(
 *     KafkaDlqErrorHandlerFactory.create(kafkaTemplate));
 * }</pre>
 */
@Slf4j
public final class KafkaDlqErrorHandlerFactory {

    private KafkaDlqErrorHandlerFactory() {}

    /**
     * Creates a {@link DefaultErrorHandler} with exponential back-off and
     * dead-letter publishing for the given {@link KafkaTemplate}.
     *
     * @param kafkaTemplate the template used to publish to DLQ topics —
     *                      must use the same bootstrap servers as the consumer
     */
    public static DefaultErrorHandler create(KafkaTemplate<String, Object> kafkaTemplate) {

        // Route failed records to <topic>.DLQ on the same partition
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (ConsumerRecord<?, ?> record, Exception ex) -> {
                    String dlqTopic = record.topic() + ".DLQ";

                    // Ensure a correlationId is present in MDC so the log pattern
                    // renders correctly. Use the record key if available, otherwise
                    // generate a new UUID for traceability.
                    boolean mdcOwned = MDC.get(SecurityContext.MDC_CORRELATION_ID) == null;
                    if (mdcOwned) {
                        String fallbackId = record.key() != null
                                ? record.key().toString()
                                : UUID.randomUUID().toString();
                        MDC.put(SecurityContext.MDC_CORRELATION_ID, fallbackId);
                    }
                    try {
                        log.error("[DLQ] Forwarding unprocessable record to {} " +
                                        "(partition={} offset={} key={}): {}",
                                dlqTopic, record.partition(), record.offset(),
                                record.key(), ex.getMessage());
                    } finally {
                        if (mdcOwned) {
                            MDC.remove(SecurityContext.MDC_CORRELATION_ID);
                        }
                    }
                    return new TopicPartition(dlqTopic, record.partition());
                });

        // Exponential back-off: 1s → 2s → 4s → 8s → 16s → DLQ (~30s total)
        ExponentialBackOff backoff = new ExponentialBackOff(1_000L, 2.0);
        backoff.setMaxElapsedTime(30_000L);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backoff);

        // Deserialization failures are permanent — send straight to DLQ, no retry
        handler.addNotRetryableExceptions(
                com.fasterxml.jackson.core.JsonParseException.class,
                com.fasterxml.jackson.databind.exc.InvalidDefinitionException.class,
                com.fasterxml.jackson.databind.exc.MismatchedInputException.class
        );

        return handler;
    }
}

