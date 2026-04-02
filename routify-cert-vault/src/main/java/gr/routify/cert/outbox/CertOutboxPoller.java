package gr.routify.cert.outbox;

import gr.routify.cert.domain.CertOutboxEvent;
import gr.routify.cert.repository.CertOutboxEventRepository;
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
 * Transactional Outbox Poller — publishes PENDING cert outbox events to Kafka.
 *
 * <h2>C6 fix — markPublished() race condition resolved</h2>
 * <p>Uses synchronous {@code kafkaTemplate.send(...).get(5, SECONDS)} so that
 * {@code markPublished()} is only called after the broker has confirmed delivery.
 * Blocking on virtual threads (Java 21 Loom) has zero thread-pool cost.
 *
 * <p>Mirrors the pattern used in routify-route-service's {@code OutboxPoller}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CertOutboxPoller {

    private final CertOutboxEventRepository outboxRepository;
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
        List<CertOutboxEvent> pending = outboxRepository.findPendingForPublishing(batchSize);
        if (pending.isEmpty()) return;

        log.debug("CertOutboxPoller: processing {} pending events", pending.size());

        for (CertOutboxEvent event : pending) {
            try {
                // Synchronous send — blocks until broker confirms (ACK) or times out.
                // markPublished() is only called on confirmed delivery (C6 fix).
                kafkaTemplate
                        .send(event.getTopic(), event.getPartitionKey(), event.getPayload())
                        .get(KAFKA_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                event.markPublished();
                log.debug("Published cert outbox event {} type={} to topic={}",
                        event.getId(), event.getEventType(), event.getTopic());

            } catch (TimeoutException e) {
                String msg = "Kafka send timed out after " + KAFKA_SEND_TIMEOUT_SECONDS + "s";
                log.error("Cert outbox event {} NOT published — {} (attempt {})",
                        event.getId(), msg, event.getRetryCount() + 1);
                event.markFailed(msg);

            } catch (Exception e) {
                String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                log.error("Cert outbox event {} NOT published — {} (attempt {})",
                        event.getId(), msg, event.getRetryCount() + 1);
                event.markFailed(msg);
            }
        }
    }

    @Scheduled(fixedDelayString = "${routify.outbox.retry-interval-ms:30000}")
    @Transactional
    public void retryFailed() {
        List<CertOutboxEvent> retryable = outboxRepository.findRetryable(maxRetries);
        if (!retryable.isEmpty()) {
            log.info("CertOutboxPoller: resetting {} failed events to PENDING for retry", retryable.size());
            retryable.forEach(CertOutboxEvent::resetToPending);
        }
    }
}

