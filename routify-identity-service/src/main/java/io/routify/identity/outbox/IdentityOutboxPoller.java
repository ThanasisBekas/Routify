package io.routify.identity.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.routify.common.event.DomainEvent;
import io.routify.identity.domain.IdentityOutboxEvent;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Transactional Outbox Poller for the identity-service.
 *
 * <p>Reads PENDING outbox entries from {@code routify_identity.outbox_event},
 * deserialises each stored JSON payload back to a typed {@link DomainEvent}, and
 * publishes it via {@link KafkaTemplate} with idempotent, acks=all semantics.
 *
 * <p>This mirrors the identical pattern used by {@code routify-route-service}
 * ({@code OutboxPoller}) and {@code routify-cert-vault} ({@code CertOutboxPoller}).
 *
 * <p><b>Graceful shutdown:</b> On JVM shutdown, {@link #shutdown()} sets a flag
 * that prevents new polls from starting and waits for any in-progress batch to
 * complete (up to 10s). This ensures the current transaction commits cleanly.
 */
@Slf4j
@Component
public class IdentityOutboxPoller {

    private final IdentityOutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public IdentityOutboxPoller(IdentityOutboxEventRepository outboxRepository,
                                KafkaTemplate<String, Object> kafkaTemplate,
                                ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate    = kafkaTemplate;
        this.objectMapper     = objectMapper;
    }

    @Value("${routify.outbox.batch-size:50}")
    private int batchSize;

    @Value("${routify.outbox.max-retries:5}")
    private int maxRetries;

    /** Kafka send timeout in seconds. Safe to block — identity-service uses virtual threads. */
    private static final long KAFKA_SEND_TIMEOUT_SECONDS = 5L;

    /** Maximum time to wait for an in-progress poll to complete on shutdown. */
    private static final long SHUTDOWN_WAIT_SECONDS = 10L;

    private volatile boolean shuttingDown = false;
    private final ReentrantLock pollLock = new ReentrantLock();

    @PreDestroy
    public void shutdown() {
        shuttingDown = true;
        log.info("IdentityOutboxPoller: shutdown requested — waiting for in-progress batch to complete");
        try {
            if (pollLock.tryLock(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                pollLock.unlock();
                log.info("IdentityOutboxPoller: in-progress batch completed — shutdown clean");
            } else {
                log.warn("IdentityOutboxPoller: timed out waiting for in-progress batch — " +
                        "transaction will roll back, PENDING events preserved for next startup");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("IdentityOutboxPoller: shutdown interrupted");
        }
    }

    /**
     * Main poll loop — fires every 250 ms (configurable).
     * Reads a batch of PENDING entries and publishes each one synchronously.
     */
    @Scheduled(fixedDelayString = "${routify.outbox.poll-interval-ms:250}")
    @Transactional
    public void pollAndPublish() {
        if (shuttingDown) return;
        pollLock.lock();
        try {
            List<IdentityOutboxEvent> pending = outboxRepository.findPendingForPublishing(batchSize);
            if (pending.isEmpty()) return;

            log.debug("IdentityOutboxPoller: processing {} pending events", pending.size());

            for (IdentityOutboxEvent outboxEvent : pending) {
                if (shuttingDown) {
                    log.info("IdentityOutboxPoller: shutdown in progress — stopping mid-batch ({} remaining)",
                            pending.size() - pending.indexOf(outboxEvent));
                    break;
                }
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
        } finally {
            pollLock.unlock();
        }
    }

    /**
     * Retry loop — fires every 30 s (configurable).
     * Resets FAILED entries back to PENDING so they are picked up by the main poll.
     */
    @Scheduled(fixedDelayString = "${routify.outbox.retry-interval-ms:30000}")
    @Transactional
    public void retryFailed() {
        if (shuttingDown) return;
        List<IdentityOutboxEvent> retryable = outboxRepository.findRetryable(maxRetries);
        if (!retryable.isEmpty()) {
            log.info("IdentityOutboxPoller: resetting {} failed events to PENDING", retryable.size());
            retryable.forEach(IdentityOutboxEvent::resetToPending);
        }
    }
}
