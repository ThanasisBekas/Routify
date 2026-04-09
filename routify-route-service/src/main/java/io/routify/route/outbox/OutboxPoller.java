package io.routify.route.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.routify.common.event.DomainEvent;
import io.routify.route.domain.OutboxEvent;
import io.routify.route.repository.OutboxEventRepository;
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
 * Transactional Outbox Poller — publishes PENDING outbox events to Kafka.
 *
 * <p>Reads each outbox entry, deserializes the stored JSON payload back to a
 * typed {@link DomainEvent}, and publishes it via {@link KafkaTemplate} backed
 * by {@link org.springframework.kafka.support.serializer.JacksonJsonSerializer}.
 * This guarantees that Kafka wire messages are always typed domain events —
 * never raw strings or maps.
 *
 * <p><b>Graceful shutdown:</b> On JVM shutdown, {@link #shutdown()} sets a flag
 * that prevents new polls from starting and waits for any in-progress batch to
 * complete (up to {@value #SHUTDOWN_WAIT_SECONDS}s). This ensures the current
 * transaction commits cleanly — events already published are marked PUBLISHED,
 * and the remaining PENDING events survive for the next startup.
 */
@Slf4j
@Component
public class OutboxPoller {

    private final OutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final GatewaySnapshotCacheEvictor cacheEvictor;

    public OutboxPoller(OutboxEventRepository outboxRepository,
                        KafkaTemplate<String, Object> kafkaTemplate,
                        ObjectMapper objectMapper,
                        GatewaySnapshotCacheEvictor cacheEvictor) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate    = kafkaTemplate;
        this.objectMapper     = objectMapper;
        this.cacheEvictor     = cacheEvictor;
    }

    @Value("${routify.outbox.batch-size:50}")
    private int batchSize;

    @Value("${routify.outbox.retry-batch-size:50}")
    private int retryBatchSize;

    @Value("${routify.outbox.max-retries:5}")
    private int maxRetries;

    /** Kafka send timeout in seconds. Safe to block on virtual threads. */
    private static final long KAFKA_SEND_TIMEOUT_SECONDS = 5L;

    /** Maximum time to wait for an in-progress poll to complete on shutdown. */
    private static final long SHUTDOWN_WAIT_SECONDS = 10L;

    private volatile boolean shuttingDown = false;
    private final ReentrantLock pollLock = new ReentrantLock();

    @PreDestroy
    public void shutdown() {
        shuttingDown = true;
        log.info("OutboxPoller: shutdown requested — waiting for in-progress batch to complete");
        try {
            if (pollLock.tryLock(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                pollLock.unlock();
                log.info("OutboxPoller: in-progress batch completed — shutdown clean");
            } else {
                log.warn("OutboxPoller: timed out waiting for in-progress batch — " +
                        "transaction will roll back, PENDING events preserved for next startup");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("OutboxPoller: shutdown interrupted");
        }
    }

    @Scheduled(fixedDelayString = "${routify.outbox.poll-interval-ms:250}")
    @Transactional
    public void pollAndPublish() {
        if (shuttingDown) return;
        pollLock.lock();
        try {
            List<OutboxEvent> pending = outboxRepository.findPendingForPublishing(batchSize);
            if (pending.isEmpty()) return;

            log.debug("OutboxPoller: processing {} pending events", pending.size());

            boolean anyPublished = false;

            for (OutboxEvent outboxEvent : pending) {
                if (shuttingDown) {
                    log.info("OutboxPoller: shutdown in progress — stopping mid-batch ({} remaining)",
                            pending.size() - pending.indexOf(outboxEvent));
                    break;
                }
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
                    anyPublished = true;
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

            // Only evict the gateway snapshot cache when at least one event was published
            if (anyPublished) {
                cacheEvictor.evict();
            }
        } finally {
            pollLock.unlock();
        }
    }


    @Scheduled(fixedDelayString = "${routify.outbox.retry-interval-ms:30000}")
    @Transactional
    public void retryFailed() {
        if (shuttingDown) return;
        List<OutboxEvent> retryable = outboxRepository.findRetryable(maxRetries, retryBatchSize);
        if (!retryable.isEmpty()) {
            log.info("OutboxPoller: resetting {} failed events to PENDING for retry", retryable.size());
            retryable.forEach(OutboxEvent::resetToPending);
        }
    }
}
