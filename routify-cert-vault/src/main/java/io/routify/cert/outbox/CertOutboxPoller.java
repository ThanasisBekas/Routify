package io.routify.cert.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.routify.cert.domain.CertOutboxEvent;
import io.routify.cert.repository.CertOutboxEventRepository;
import io.routify.common.event.DomainEvent;
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
 * Transactional Outbox Poller — publishes PENDING cert outbox events to Kafka.
 *
 * <p>Reads each outbox entry, deserializes the stored JSON payload back to a
 * typed {@link DomainEvent}, and publishes it via {@link KafkaTemplate} backed
 * by {@link org.springframework.kafka.support.serializer.JacksonJsonSerializer}.
 * This guarantees that Kafka wire messages are always typed domain events —
 * never raw strings or maps.
 *
 * <p><b>Graceful shutdown:</b> On JVM shutdown, {@link #shutdown()} sets a flag
 * that prevents new polls from starting and waits for any in-progress batch to
 * complete (up to 10s). This ensures the current transaction commits cleanly.
 */
@Slf4j
@Component
public class CertOutboxPoller {

    private final CertOutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public CertOutboxPoller(CertOutboxEventRepository outboxRepository,
                            KafkaTemplate<String, Object> kafkaTemplate,
                            ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate    = kafkaTemplate;
        this.objectMapper     = objectMapper;
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
        log.info("CertOutboxPoller: shutdown requested — waiting for in-progress batch to complete");
        try {
            if (pollLock.tryLock(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                pollLock.unlock();
                log.info("CertOutboxPoller: in-progress batch completed — shutdown clean");
            } else {
                log.warn("CertOutboxPoller: timed out waiting for in-progress batch — " +
                        "transaction will roll back, PENDING events preserved for next startup");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("CertOutboxPoller: shutdown interrupted");
        }
    }

    @Scheduled(fixedDelayString = "${routify.outbox.poll-interval-ms:250}")
    @Transactional
    public void pollAndPublish() {
        if (shuttingDown) return;
        pollLock.lock();
        try {
            List<CertOutboxEvent> pending = outboxRepository.findPendingForPublishing(batchSize);
            if (pending.isEmpty()) return;

            log.debug("CertOutboxPoller: processing {} pending events", pending.size());

            for (CertOutboxEvent outboxEvent : pending) {
                if (shuttingDown) {
                    log.info("CertOutboxPoller: shutdown in progress — stopping mid-batch ({} remaining)",
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
                    log.debug("Published cert outbox event {} type={} to topic={}",
                            outboxEvent.getId(), outboxEvent.getEventType(), outboxEvent.getTopic());

                } catch (TimeoutException e) {
                    String msg = "Kafka send timed out after " + KAFKA_SEND_TIMEOUT_SECONDS + "s";
                    log.error("Cert outbox event {} NOT published — {} (attempt {})",
                            outboxEvent.getId(), msg, outboxEvent.getRetryCount() + 1);
                    outboxEvent.markFailed(msg);

                } catch (Exception e) {
                    String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                    log.error("Cert outbox event {} NOT published — {} (attempt {})",
                            outboxEvent.getId(), msg, outboxEvent.getRetryCount() + 1);
                    outboxEvent.markFailed(msg);
                }
            }
        } finally {
            pollLock.unlock();
        }
    }

    @Scheduled(fixedDelayString = "${routify.outbox.retry-interval-ms:30000}")
    @Transactional
    public void retryFailed() {
        if (shuttingDown) return;
        List<CertOutboxEvent> retryable = outboxRepository.findRetryable(maxRetries);
        if (!retryable.isEmpty()) {
            log.info("CertOutboxPoller: resetting {} failed events to PENDING for retry", retryable.size());
            retryable.forEach(CertOutboxEvent::resetToPending);
        }
    }
}
