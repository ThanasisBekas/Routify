package gr.routify.audit.consumer;

import gr.routify.audit.domain.DlqEvent;
import gr.routify.audit.repository.DlqEventRepository;
import gr.routify.common.event.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Consumes every Kafka Dead-Letter Queue topic and persists the failed records
 * to {@code routify_audit.dlq_event} for visibility, alerting and post-mortem analysis.
 *
 * <p>DLQ topics follow the naming convention {@code <original-topic>.DLQ} and are
 * populated by {@link gr.routify.common.kafka.KafkaDlqErrorHandlerFactory} after a
 * consumer exhausts its exponential back-off retry budget or encounters a non-retryable
 * exception (e.g. malformed JSON).
 *
 * <p><b>Raw String consumption:</b> Records arrive here precisely because they could
 * not be processed — they may carry unparseable payloads. The listener therefore
 * receives the raw {@link ConsumerRecord}{@code <String, String>} and stores the value
 * as-is without attempting any further deserialisation.
 *
 * <p><b>Dedicated container factory:</b> Uses {@code dlqListenerContainerFactory} which
 * has <em>no</em> DLQ error handler. If storing a DLQ record fails it is logged and
 * acknowledged to prevent an infinite DLQ→DLQ loop.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DlqEventConsumer {

    private final DlqEventRepository dlqEventRepository;

    @KafkaListener(
            topics = {
                    KafkaTopics.DLQ_ROUTE_EVENTS,
                    KafkaTopics.DLQ_FILTER_EVENTS,
                    KafkaTopics.DLQ_TENANT_EVENTS,
                    KafkaTopics.DLQ_USER_EVENTS,
                    KafkaTopics.DLQ_GATEWAY_RELOAD,
                    KafkaTopics.DLQ_GATEWAY_CONFIG,
                    KafkaTopics.DLQ_CERT_EVENTS,
                    KafkaTopics.DLQ_CERT_GROUP_EVENTS,
                    KafkaTopics.DLQ_REQUEST_TELEMETRY,
                    KafkaTopics.DLQ_ROUTE_COMMANDS,
                    KafkaTopics.DLQ_FILTER_COMMANDS,
                    KafkaTopics.DLQ_USER_COMMANDS,
                    KafkaTopics.DLQ_TENANT_COMMANDS,
                    KafkaTopics.DLQ_AUTH_COMMANDS,
                    KafkaTopics.DLQ_CERT_COMMANDS,
                    KafkaTopics.DLQ_AI_MODIFICATION_EVENTS
            },
            groupId = "routify-audit-dlq",
            containerFactory = "dlqListenerContainerFactory"
    )
    @Transactional
    public void onDlqRecord(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String dlqTopic    = record.topic();
        String sourceTopic = dlqTopic.endsWith(".DLQ")
                ? dlqTopic.substring(0, dlqTopic.length() - 4)
                : dlqTopic;

        // Extract exception info from the standard Spring Kafka DLQ headers if present
        String errorMessage = extractHeader(record, "kafka_dlt-exception-message");
        String errorClass   = extractHeader(record, "kafka_dlt-exception-fqcn");

        try {
            DlqEvent event = DlqEvent.builder()
                    .sourceTopic(sourceTopic)
                    .dlqTopic(dlqTopic)
                    .partitionNum(record.partition())
                    .kafkaOffset(record.offset())
                    .recordKey(record.key())
                    .rawPayload(record.value())
                    .errorMessage(errorMessage)
                    .errorClass(errorClass)
                    .failedAt(Instant.now())
                    .build();

            dlqEventRepository.save(event);
            ack.acknowledge();

            log.warn("DLQ record persisted: topic={} partition={} offset={} error={}",
                    dlqTopic, record.partition(), record.offset(), errorMessage);

        } catch (Exception e) {
            // Acknowledge anyway — we must not loop DLQ→DLQ.
            // The failure is visible in logs and metrics.
            log.error("Failed to persist DLQ record from topic={} partition={} offset={}: {}",
                    dlqTopic, record.partition(), record.offset(), e.getMessage(), e);
            ack.acknowledge();
        }
    }

    private String extractHeader(ConsumerRecord<?, ?> record, String headerName) {
        var header = record.headers().lastHeader(headerName);
        if (header == null) return null;
        try {
            return new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return null;
        }
    }
}

