package gr.routify.route.outbox;

import gr.routify.common.event.DomainEvent;
import gr.routify.common.event.KafkaTopics;
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
 * <h2>C6 fix — markPublished() race condition resolved</h2>
 * <p>The previous implementation called {@code kafkaTemplate.send()} (async) and
 * then immediately called {@code event.markPublished()} on the main thread, before
 * Kafka had confirmed delivery. If the broker rejected the message the
 * {@code whenComplete} callback logged an error but the event was already
 * permanently marked PUBLISHED — causing silent event loss.
 *
 * <p>This version uses {@code kafkaTemplate.send(...).get(5, SECONDS)} to block
 * until the broker ACKs (or rejects) the record. Because all services run with
 * {@code spring.threads.virtual.enabled: true}, this blocking call pins a virtual
 * thread rather than a platform thread, so there is zero thread-pool starvation.
 *
 * <p>{@code markPublished()} is now only called after a confirmed ACK.
 * {@code markFailed()} is called on timeout or any broker error so the retry
 * poller can attempt re-delivery on the next cycle.
 *
 * <p>Polling interval: every 250ms (configurable via {@code routify.outbox.poll-interval-ms}).<br>
 * Poll batch size: configurable via {@code routify.outbox.batch-size} (default 50).<br>
 * Retry batch size: configurable via {@code routify.outbox.retry-batch-size} (default 50).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPoller {

    private final OutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

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

        for (OutboxEvent event : pending) {
            try {
                // Synchronous send — blocks until broker confirms (ACK) or times out.
                // markPublished() is only called on confirmed delivery (C6 fix).
                kafkaTemplate
                        .send(event.getTopic(), event.getPartitionKey(), event.getPayload())
                        .get(KAFKA_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                event.markPublished();
                log.debug("Published outbox event {} type={} to topic={}",
                        event.getId(), event.getEventType(), event.getTopic());

            } catch (TimeoutException e) {
                String msg = "Kafka send timed out after " + KAFKA_SEND_TIMEOUT_SECONDS + "s";
                log.error("Outbox event {} NOT published — {} (attempt {})",
                        event.getId(), msg, event.getRetryCount() + 1);
                event.markFailed(msg);

            } catch (Exception e) {
                String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                log.error("Outbox event {} NOT published — {} (attempt {})",
                        event.getId(), msg, event.getRetryCount() + 1);
                event.markFailed(msg);
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

