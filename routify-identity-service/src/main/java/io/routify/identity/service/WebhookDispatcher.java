package io.routify.identity.service;

import io.routify.identity.domain.WebhookDelivery;
import io.routify.identity.domain.WebhookSubscription;
import io.routify.identity.repository.WebhookSubscriptionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Asynchronous HTTP webhook delivery engine.
 *
 * <p>For each matching subscription, builds a JSON payload, computes the HMAC-SHA256
 * signature, and POSTs to the subscription URL with standard Routify webhook headers.
 *
 * <p>Retry logic: up to 3 attempts with exponential backoff (30 s → 2 min → 15 min).
 * After 3 failed attempts the delivery is marked FAILED and the subscription's failure
 * count is incremented. If the failure count reaches 10, the subscription is auto-suspended.
 */
@Slf4j
@Component
public class WebhookDispatcher {

    private final WebhookService webhookService;
    private final WebhookSubscriptionRepository subscriptionRepo;
    private final RestClient restClient;

    public WebhookDispatcher(WebhookService webhookService,
                             WebhookSubscriptionRepository subscriptionRepo) {
        this.webhookService = webhookService;
        this.subscriptionRepo = subscriptionRepo;
        this.restClient = RestClient.builder()
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    /**
     * Dispatches a webhook delivery for the given subscription.
     * This method is invoked asynchronously from the Kafka event consumer.
     */
    @Async
    public void dispatch(WebhookSubscription subscription, String eventType, String payloadJson) {
        WebhookDelivery delivery = webhookService.createDelivery(
                subscription.getId(), eventType, payloadJson);
        attemptDelivery(subscription, delivery, payloadJson);
    }

    /**
     * Sends a synchronous test ping to a webhook subscription.
     * Returns the result immediately (not queued).
     */
    public TestPingResult testPing(WebhookSubscription subscription) {
        String testPayload = "{\"eventType\":\"TEST_PING\",\"timestamp\":\""
                + Instant.now() + "\",\"data\":{\"message\":\"Routify webhook test ping\"}}";
        try {
            String signature = computeHmac(subscription.getSecret(), testPayload);
            var response = restClient.post()
                    .uri(subscription.getUrl())
                    .header("X-Routify-Signature", "sha256=" + signature)
                    .header("X-Routify-Event", "TEST_PING")
                    .header("X-Routify-Delivery", UUID.randomUUID().toString())
                    .body(testPayload)
                    .retrieve()
                    .toEntity(String.class);

            int status = response.getStatusCode().value();
            boolean success = response.getStatusCode().is2xxSuccessful();
            return new TestPingResult(success, status,
                    success ? "Test ping delivered successfully" : "Non-2xx response: " + status);
        } catch (Exception e) {
            log.warn("Webhook test ping failed for subscription={}: {}", subscription.getId(), e.getMessage());
            return new TestPingResult(false, null, "Delivery failed: " + e.getMessage());
        }
    }

    /**
     * Scheduled retry poller: picks up PENDING deliveries whose nextRetryAt has passed.
     * Runs every 30 seconds.
     */
    @Scheduled(fixedDelay = 30_000)
    public void retryPendingDeliveries() {
        List<WebhookDelivery> pending = webhookService.findPendingRetries();
        for (WebhookDelivery delivery : pending) {
            try {
                subscriptionRepo.findById(delivery.getSubscriptionId()).ifPresent(sub ->
                        attemptDelivery(sub, delivery, delivery.getPayload()));
            } catch (Exception e) {
                log.warn("Retry failed for delivery={}: {}", delivery.getId(), e.getMessage());
            }
        }
    }

    // ─── Private ───────────────────────────────────────────────────────────────

    private void attemptDelivery(WebhookSubscription subscription, WebhookDelivery delivery, String payloadJson) {
        try {
            String signature = computeHmac(subscription.getSecret(), payloadJson);
            var response = restClient.post()
                    .uri(subscription.getUrl())
                    .header("X-Routify-Signature", "sha256=" + signature)
                    .header("X-Routify-Event", delivery.getEventType())
                    .header("X-Routify-Delivery", delivery.getId().toString())
                    .body(payloadJson)
                    .retrieve()
                    .toEntity(String.class);

            int status = response.getStatusCode().value();
            if (response.getStatusCode().is2xxSuccessful()) {
                webhookService.markDelivered(delivery, status, response.getBody());
                log.debug("Webhook delivered: subscription={} delivery={} status={}",
                        subscription.getId(), delivery.getId(), status);
            } else {
                webhookService.markDeliveryFailed(delivery,
                        "Non-2xx response: " + status, status, response.getBody());
                log.warn("Webhook delivery non-2xx: subscription={} delivery={} status={}",
                        subscription.getId(), delivery.getId(), status);
            }
        } catch (Exception e) {
            webhookService.markDeliveryFailed(delivery,
                    e.getMessage(), null, null);
            log.warn("Webhook delivery failed: subscription={} delivery={} error={}",
                    subscription.getId(), delivery.getId(), e.getMessage());
        }
    }

    static String computeHmac(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 computation failed", e);
        }
    }

    public record TestPingResult(boolean success, Integer responseStatus, String message) {}
}

