package io.routify.gitops.reconcile;

import io.routify.gitops.config.GitOpsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

/**
 * Sends webhook notifications about reconciliation results.
 *
 * <p>Signs payloads with HMAC-SHA256 using the configured webhook secret.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebhookNotifier {

    private final GitOpsProperties properties;
    private final RestTemplate webhookRestTemplate = new RestTemplate();

    /**
     * Fires a webhook notification with the given reconciliation result.
     */
    public void notify(ReconciliationResult result) {
        if (StringUtils.isBlank(properties.getWebhookUrl())) {
            log.debug("No webhook URL configured — skipping notification");
            return;
        }

        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("event", mapOutcomeToEvent(result.outcome()));
            payload.put("timestamp", result.timestamp().toString());
            payload.put("commitHash", result.commitHash() != null ? result.commitHash() : "");
            payload.put("configHash", result.configHash() != null ? result.configHash() : "");
            payload.put("outcome", result.outcome().name());
            payload.put("routesCreated", result.routesCreated());
            payload.put("routesUpdated", result.routesUpdated());
            payload.put("filtersCreated", result.filtersCreated());
            payload.put("filtersUpdated", result.filtersUpdated());
            payload.put("warnings", result.warnings() != null ? result.warnings() : java.util.List.of());
            payload.put("errorMessage", result.errorMessage() != null ? result.errorMessage() : "");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // Sign payload with HMAC-SHA256 if secret is configured
            if (StringUtils.isNotBlank(properties.getWebhookSecret())) {
                String payloadJson = new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(payload);
                String signature = computeHmacSha256(payloadJson, properties.getWebhookSecret());
                headers.set("X-Hub-Signature-256", "sha256=" + signature);
            }

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);

            log.debug("Sending webhook notification to {} for outcome {}",
                    properties.getWebhookUrl(), result.outcome());

            ResponseEntity<String> response = webhookRestTemplate.exchange(
                    properties.getWebhookUrl(), HttpMethod.POST, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("Webhook notification sent successfully for outcome {}", result.outcome());
            } else {
                log.warn("Webhook returned non-2xx status: {}", response.getStatusCode());
            }
        } catch (RestClientException e) {
            log.error("Failed to send webhook notification: {}", e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error sending webhook notification: {}", e.getMessage(), e);
        }
    }

    private String mapOutcomeToEvent(ReconciliationResult.ReconciliationOutcome outcome) {
        return switch (outcome) {
            case APPLIED -> "RECONCILIATION_SUCCEEDED";
            case DRIFT_DETECTED -> "DRIFT_DETECTED";
            case FAILED -> "RECONCILIATION_FAILED";
            case NO_CHANGE -> "NO_CHANGE";
        };
    }

    public static String computeHmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hmac);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256", e);
        }
    }
}

