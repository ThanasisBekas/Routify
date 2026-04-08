package io.routify.gitops.controller;

import io.routify.gitops.config.GitOpsProperties;
import io.routify.gitops.reconcile.ReconciliationResult;
import io.routify.gitops.reconcile.ReconciliationService;
import io.routify.gitops.reconcile.WebhookNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Webhook endpoint for receiving Git push events from GitHub or GitLab.
 *
 * <p>When a push event is received for the configured branch, triggers
 * an immediate reconciliation cycle instead of waiting for the next poll.
 *
 * <p>Endpoint: {@code POST /api/v1/gitops/webhook}
 *
 * <p>Validates the webhook signature using HMAC-SHA256 (GitHub format:
 * {@code X-Hub-Signature-256: sha256=<hmac>}).
 */
@RestController
@RequestMapping("/api/v1/gitops")
@RequiredArgsConstructor
@Slf4j
public class GitWebhookController {

    private final ReconciliationService reconciliationService;
    private final GitOpsProperties properties;

    /**
     * Receives a Git push webhook and triggers reconciliation.
     *
     * @param signature    the HMAC-SHA256 signature from the webhook source
     * @param event        the GitHub event type header (e.g. "push")
     * @param body         the raw request body
     * @return 200 OK if accepted, 400 if validation fails
     */
    @PostMapping("/webhook")
    public ResponseEntity<Map<String, String>> handleWebhook(
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Event", required = false) String event,
            @RequestHeader(value = "X-GitLab-Event", required = false) String gitlabEvent,
            @RequestBody String body) {

        log.info("Received Git webhook push event (GitHub event: {}, GitLab event: {})",
                event, gitlabEvent);

        // Validate signature if webhook secret is configured
        if (StringUtils.isNotBlank(properties.getWebhookSecret())) {
            if (StringUtils.isBlank(signature)) {
                log.warn("Webhook signature missing — rejecting request");
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Missing X-Hub-Signature-256 header"));
            }

            String expectedSignature = "sha256=" +
                    WebhookNotifier.computeHmacSha256(body, properties.getWebhookSecret());

            if (!signature.equals(expectedSignature)) {
                log.warn("Webhook signature mismatch — rejecting request");
                return ResponseEntity.status(403)
                        .body(Map.of("error", "Invalid webhook signature"));
            }
        }

        // For GitHub: only process "push" events
        if (StringUtils.isNotBlank(event) && !"push".equals(event)) {
            log.debug("Ignoring non-push GitHub event: {}", event);
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "Not a push event"));
        }

        // For GitLab: only process "Push Hook" events
        if (StringUtils.isNotBlank(gitlabEvent) && !"Push Hook".equals(gitlabEvent)) {
            log.debug("Ignoring non-push GitLab event: {}", gitlabEvent);
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "Not a push event"));
        }

        // Check if the push is for the configured branch
        if (body.contains("\"ref\"")) {
            String expectedRef = "refs/heads/" + properties.getBranch();
            if (!body.contains(expectedRef)) {
                log.debug("Push event for non-configured branch — ignoring");
                return ResponseEntity.ok(Map.of("status", "ignored",
                        "reason", "Push not for branch " + properties.getBranch()));
            }
        }

        // Trigger immediate reconciliation
        log.info("Triggering immediate reconciliation from Git webhook");
        ReconciliationResult result = reconciliationService.reconcile();

        return ResponseEntity.ok(Map.of(
                "status", "triggered",
                "outcome", result.outcome().name()
        ));
    }

    /**
     * Manual sync trigger — forces an immediate reconciliation cycle.
     * Used by the dashboard "Sync Now" button.
     */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, String>> triggerSync() {
        log.info("Manual sync triggered");
        ReconciliationResult result = reconciliationService.reconcile();
        return ResponseEntity.ok(Map.of(
                "status", "triggered",
                "outcome", result.outcome().name()
        ));
    }
}

