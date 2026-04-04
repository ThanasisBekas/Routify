package gr.routify.identity.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.common.event.DomainEvent;
import gr.routify.identity.domain.IdentityOutboxEvent;
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
 * Transactional Outbox Poller for the identity-service.
 *
 * <p>Reads PENDING outbox entries from {@code routify_identity.outbox_event},
 * deserialises each stored JSON payload back to a typed {@link DomainEvent}, and
 * publishes it via {@link KafkaTemplate} with idempotent, acks=all semantics.
 *
 * <p>This mirrors the identical pattern used by {@code routify-route-service}
 * ({@code OutboxPoller}) and {@code routify-cert-vault} ({@code CertOutboxPoller}).
 * The three pollers share the same algorithm; consolidation into a shared
 * {@code routify-common} base class is tracked as improvement D6.
 *
 * <p>Key guarantees:
 * <ul>
 *   <li>At-least-once delivery — events are only marked PUBLISHED after broker ACK.</li>
 *   <li>No event loss — Kafka unavailability causes FAILED status + automatic retry.</li>
 *   <li>No duplicate DB entries — the event was committed atomically with the entity.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdentityOutboxPoller {

    private final IdentityOutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${routify.outbox.batch-size:50}")
    private int batchSize;

    @Value("${routify.outbox.max-retries:5}")
    private int maxRetries;

    /** Kafka send timeout in seconds. Safe to block — identity-service uses virtual threads. */
    private static final long KAFKA_SEND_TIMEOUT_SECONDS = 5L;

    /**
     * Main poll loop — fires every 250 ms (configurable).
     * Reads a batch of PENDING entries and publishes each one synchronously.
     */
    @Scheduled(fixedDelayString = "${routify.outbox.poll-interval-ms:250}")
    @Transactional
    public void pollAndPublish() {
        List<IdentityOutboxEvent> pending = outboxRepository.findPendingForPublishing(batchSize);
        if (pending.isEmpty()) return;

        log.debug("IdentityOutboxPoller: processing {} pending events", pending.size());

        for (IdentityOutboxEvent outboxEvent : pending) {
            try {
                DomainEvent domainEvent = objectMapper.readValue(
                        outboxEvent.getPayload(), DomainEvent.class);

                kafkaTemplate
                        .send(outboxEvent.getTopic(), outboxEvent.getPartitionKey(), domainEvent)
                        .get(KAFKA_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                outboxEvent.markPublished();

                log.debug("IdentityOutboxPoller: published {} aggregateId={} topic={}",
                        outboxEvent.getEventType(),
                        outboxEvent.getAggregateId(),
                        outboxEvent.getTopic());

            } catch (TimeoutException e) {
                String msg = "Kafka send timed out after " + KAFKA_SEND_TIMEOUT_SECONDS + "s";
                log.error("Identity outbox event {} NOT published — {} (attempt {})",
                        outboxEvent.getId(), msg, outboxEvent.getRetryCount() + 1);
                outboxEvent.markFailed(msg);

            } catch (Exception e) {
                String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                log.error("Identity outbox event {} NOT published — {} (attempt {})",
                        outboxEvent.getId(), msg, outboxEvent.getRetryCount() + 1);
                outboxEvent.markFailed(msg);
            }
        }
    }

    /**
     * Retry loop — fires every 30 s (configurable).
     * Resets FAILED entries back to PENDING so they are picked up by the main poll.
     */
    @Scheduled(fixedDelayString = "${routify.outbox.retry-interval-ms:30000}")
    @Transactional
    public void retryFailed() {
        List<IdentityOutboxEvent> retryable = outboxRepository.findRetryable(maxRetries);
        if (!retryable.isEmpty()) {
            log.info("IdentityOutboxPoller: resetting {} failed events to PENDING", retryable.size());
            retryable.forEach(IdentityOutboxEvent::resetToPending);
        }
    }
}

