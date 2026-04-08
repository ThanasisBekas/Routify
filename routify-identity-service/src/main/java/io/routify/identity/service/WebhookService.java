package io.routify.identity.service;

import io.routify.common.exception.RoutifyException;
import io.routify.common.observability.RoutifyMetrics;
import io.routify.identity.domain.WebhookDelivery;
import io.routify.identity.domain.WebhookSubscription;
import io.routify.identity.repository.WebhookDeliveryRepository;
import io.routify.identity.repository.WebhookSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Webhook subscription lifecycle management — CRUD, delivery log queries, retention cleanup.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    private static final String BASE62 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SECRET_LENGTH = 40;
    private static final int AUTO_SUSPEND_THRESHOLD = 10;

    private final WebhookSubscriptionRepository subscriptionRepo;
    private final WebhookDeliveryRepository deliveryRepo;
    private final RoutifyMetrics metrics;
    private final TransactionTemplate transactionTemplate;

    @Value("${routify.webhooks.delivery-retention-days:7}")
    private int retentionDays;

    @Value("${routify.webhooks.cleanup-batch-size:1000}")
    private int cleanupBatchSize;

    // ─── Subscription Queries ──────────────────────────────────────────────────

    public Page<WebhookSubscription> findAll(UUID tenantId, Pageable pageable) {
        return subscriptionRepo.findByTenantId(tenantId, pageable);
    }

    public WebhookSubscription findById(UUID id, UUID tenantId) {
        return subscriptionRepo.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new RoutifyException.NotFound("WebhookSubscription", id.toString()));
    }

    public List<WebhookSubscription> findActiveByTenantIdAndEventType(UUID tenantId, String eventType) {
        return subscriptionRepo.findActiveByTenantIdAndEventType(tenantId, eventType);
    }

    // ─── Subscription Commands ─────────────────────────────────────────────────

    @Transactional
    public WebhookSubscription create(UUID tenantId, String name, String url,
                                       List<String> eventTypes, UUID createdBy) {
        String secret = generateSecret();
        WebhookSubscription sub = new WebhookSubscription(tenantId, name, url, secret, eventTypes, createdBy);
        sub = subscriptionRepo.save(sub);
        log.info("Webhook subscription created: id={} name={} tenant={}", sub.getId(), name, tenantId);
        return sub;
    }

    @Transactional
    public WebhookSubscription update(UUID id, UUID tenantId, String name, String url,
                                       List<String> eventTypes) {
        WebhookSubscription sub = findById(id, tenantId);
        if (name != null) sub.setName(name);
        if (url != null) sub.setUrl(url);
        if (eventTypes != null) sub.setEventTypes(eventTypes);
        sub = subscriptionRepo.save(sub);
        log.info("Webhook subscription updated: id={} tenant={}", id, tenantId);
        return sub;
    }

    @Transactional
    public void delete(UUID id, UUID tenantId) {
        WebhookSubscription sub = findById(id, tenantId);
        sub.setStatus(WebhookSubscription.Status.DELETED);
        subscriptionRepo.save(sub);
        log.info("Webhook subscription deleted: id={} tenant={}", id, tenantId);
    }

    // ─── Delivery Management ───────────────────────────────────────────────────

    @Transactional
    public WebhookDelivery createDelivery(UUID subscriptionId, String eventType, String payload) {
        WebhookDelivery delivery = new WebhookDelivery(subscriptionId, eventType, payload);
        return deliveryRepo.save(delivery);
    }

    public Page<WebhookDelivery> findDeliveries(UUID subscriptionId, Pageable pageable) {
        return deliveryRepo.findBySubscriptionIdOrderByCreatedAtDesc(subscriptionId, pageable);
    }

    public List<WebhookDelivery> findPendingRetries() {
        return deliveryRepo.findPendingRetries(Instant.now());
    }

    @Transactional
    public void markDelivered(WebhookDelivery delivery, int httpStatus, String body) {
        delivery.markDelivered(httpStatus, body);
        deliveryRepo.save(delivery);

        // Update subscription stats
        subscriptionRepo.findById(delivery.getSubscriptionId()).ifPresent(sub -> {
            sub.markDelivered();
            subscriptionRepo.save(sub);
        });
    }

    @Transactional
    public void markDeliveryFailed(WebhookDelivery delivery, String error, Integer httpStatus, String body) {
        if (delivery.getAttempt() < 3) {
            // Schedule retry with exponential backoff: 30s, 2min, 15min
            Instant nextRetry = switch (delivery.getAttempt()) {
                case 1 -> Instant.now().plusSeconds(30);
                case 2 -> Instant.now().plusSeconds(120);
                default -> Instant.now().plusSeconds(900);
            };
            delivery.markPendingRetry(nextRetry);
            delivery.setErrorMessage(error != null ? error.substring(0, Math.min(error.length(), 2000)) : null);
            delivery.setResponseStatus(httpStatus);
            delivery.setResponseBody(body != null ? body.substring(0, Math.min(body.length(), 4000)) : null);
            deliveryRepo.save(delivery);
        } else {
            delivery.markFailed(error, httpStatus, body);
            deliveryRepo.save(delivery);

            // Increment subscription failure count, auto-suspend if threshold reached
            subscriptionRepo.findById(delivery.getSubscriptionId()).ifPresent(sub -> {
                sub.incrementFailureCount();
                if (sub.getFailureCount() >= AUTO_SUSPEND_THRESHOLD) {
                    sub.suspend();
                    log.warn("Webhook subscription auto-suspended: id={} failureCount={}",
                            sub.getId(), sub.getFailureCount());
                }
                subscriptionRepo.save(sub);
            });
        }
    }

    // ─── Retention Cleanup ─────────────────────────────────────────────────────

    /**
     * Purges webhook delivery records older than the configured retention period.
     * Runs nightly at 3:00 AM.
     *
     * <p>Deletion is performed in batches of {@code routify.webhooks.cleanup-batch-size}
     * (default 1000) to prevent long-held table locks on high-volume deployments.
     * Each batch runs in its own transaction via {@link TransactionTemplate}.
     * The loop continues until a batch deletes fewer rows than the batch size,
     * indicating all expired records have been purged.
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void cleanupOldDeliveries() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        log.info("Webhook delivery cleanup started: retentionDays={} cutoff={} batchSize={}",
                retentionDays, cutoff, cleanupBatchSize);

        int totalDeleted = 0;
        int batchDeleted;
        int iterations = 0;

        do {
            batchDeleted = deleteOneBatch(cutoff);
            totalDeleted += batchDeleted;
            iterations++;
        } while (batchDeleted >= cleanupBatchSize);

        if (totalDeleted > 0) {
            log.info("Webhook delivery cleanup completed: deleted {} records in {} batches (retention={} days)",
                    totalDeleted, iterations, retentionDays);
        } else {
            log.debug("Webhook delivery cleanup completed: no expired records found");
        }
        metrics.recordWebhookDeliveryCleanup(totalDeleted);
    }

    /**
     * Deletes a single batch of expired delivery records within its own transaction.
     * Uses {@link TransactionTemplate} to ensure each batch commits independently,
     * avoiding the Spring AOP self-invocation pitfall.
     *
     * @param cutoff records with {@code created_at} before this instant are eligible
     * @return the number of deleted rows (≤ {@code cleanupBatchSize})
     */
    int deleteOneBatch(Instant cutoff) {
        Integer deleted = transactionTemplate.execute(_ ->
                deliveryRepo.deleteOlderThanBatch(cutoff, cleanupBatchSize));
        return deleted != null ? deleted : 0;
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private static String generateSecret() {
        StringBuilder sb = new StringBuilder("whsec_");
        for (int i = 0; i < SECRET_LENGTH; i++) {
            sb.append(BASE62.charAt(RANDOM.nextInt(BASE62.length())));
        }
        return sb.toString();
    }
}

