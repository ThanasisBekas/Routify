package gr.routify.route.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.common.event.DomainEvent;
import gr.routify.route.domain.OutboxEvent;
import gr.routify.route.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Transactional Outbox Poller — publishes PENDING outbox events to Kafka.
 *
 * <p>Reads each outbox entry, deserializes the stored JSON payload back to a
 * typed {@link DomainEvent}, and publishes it via {@link KafkaTemplate} backed
 * by {@link org.springframework.kafka.support.serializer.JsonSerializer}.
 * This guarantees that Kafka wire messages are always typed domain events —
 * never raw strings or maps.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPoller {

    private final OutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${routify.outbox.batch-size:50}")
    private int batchSize;

    @Value("${routify.outbox.retry-batch-size:50}")
    private int retryBatchSize;

    @Value("${routify.outbox.max-retries:5}")
    private int maxRetries;

    /** Kafka send timeout in seconds. Safe to block on virtual threads. */
    private static final long KAFKA_SEND_TIMEOUT_SECONDS = 5L;

    @Scheduled(fixedDelayString = "${routify.outbox.poll-interval-ms:250}")
    @Transactional
    public void pollAndPublish() {
        List<OutboxEvent> pending = outboxRepository.findPendingForPublishing(batchSize);
        if (pending.isEmpty()) return;

        log.debug("OutboxPoller: processing {} pending events", pending.size());

        for (OutboxEvent outboxEvent : pending) {
            try {
                // Deserialize stored JSON back to a typed DomainEvent so the wire
                // message is always a typed event — never a raw string.
                DomainEvent domainEvent = objectMapper.readValue(outboxEvent.getPayload(), DomainEvent.class);

                // Synchronous send — blocks until broker confirms (ACK) or times out.
                // markPublished() is only called on confirmed delivery (C6 fix).
                kafkaTemplate
                        .send(outboxEvent.getTopic(), outboxEvent.getPartitionKey(), domainEvent)
                        .get(KAFKA_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                outboxEvent.markPublished();
                log.debug("Published outbox event {} type={} to topic={}",
                        outboxEvent.getId(), outboxEvent.getEventType(), outboxEvent.getTopic());

            } catch (TimeoutException e) {
                String msg = "Kafka send timed out after " + KAFKA_SEND_TIMEOUT_SECONDS + "s";
                log.error("Outbox event {} NOT published — {} (attempt {})",
                        outboxEvent.getId(), msg, outboxEvent.getRetryCount() + 1);
                outboxEvent.markFailed(msg);

            } catch (Exception e) {
                String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                log.error("Outbox event {} NOT published — {} (attempt {})",
                        outboxEvent.getId(), msg, outboxEvent.getRetryCount() + 1);
                outboxEvent.markFailed(msg);
            }
        }
    }

    @Scheduled(fixedDelayString = "${routify.outbox.retry-interval-ms:30000}")
    @Transactional
    public void retryFailed() {
        List<OutboxEvent> retryable = outboxRepository.findRetryable(maxRetries);
        if (!retryable.isEmpty()) {
            log.info("OutboxPoller: resetting {} failed events to PENDING for retry", retryable.size());
            retryable.forEach(OutboxEvent::resetToPending);
        }
    }
}
