package io.routify.gateway.filter.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.routify.common.web.RoutifyHeaders;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Fires a non-blocking webhook HTTP POST when a request matches configurable
 * conditions (status codes, header values). Unlike the platform-level webhook
 * system (which reacts to domain events), this filter operates at the
 * <strong>request level</strong> — useful for real-time alerting on specific
 * traffic patterns.
 *
 * <h3>Trigger conditions:</h3>
 * <ul>
 *   <li>{@code 5xx} — any 500–599 status code (default)</li>
 *   <li>{@code 4xx} — any 400–499 status code</li>
 *   <li>{@code ALL} — any status code</li>
 *   <li>Comma-separated codes, e.g. {@code 503,504}</li>
 * </ul>
 *
 * <h3>HMAC signing:</h3>
 * When {@code secret} is configured, the payload body is signed with HMAC-SHA256
 * and attached as {@code X-Routify-Signature: sha256=<hex>}.
 *
 * <h3>Cooldown:</h3>
 * Max 1 webhook per route per {@code cooldownSeconds} (default 10) to prevent
 * notification storms under high error rates.
 *
 * <p>Filter type: {@code WEBHOOK_NOTIFY}
 */
@Slf4j
@Component
public class WebhookNotifyGatewayFilterFactory
        extends AbstractGatewayFilterFactory<WebhookNotifyGatewayFilterFactory.Config> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern COMMA_SPLIT = Pattern.compile("\\s*,\\s*");
    private static final String HMAC_ALGO = "HmacSHA256";
    private static final Duration WEBHOOK_TIMEOUT = Duration.ofSeconds(5);

    /** Headers that are always redacted from the webhook payload. */
    private static final Set<String> REDACTED_HEADERS = Set.of(
            "authorization", "cookie", "x-api-key", "x-routify-signature",
            "proxy-authorization", "set-cookie");

    /** Per-route cooldown tracker: routeId → last notification timestamp. */
    private final Map<String, Instant> lastNotification = new ConcurrentHashMap<>();

    private final WebClient webClient;

    public WebhookNotifyGatewayFilterFactory(WebClient.Builder webClientBuilder) {
        super(Config.class);
        this.webClient = webClientBuilder.build();
    }

    @Override
    public GatewayFilter apply(Config config) {
        String webhookUrl = config.getWebhookUrl();
        String secret = config.getSecret();
        long cooldownSeconds = config.getCooldownSeconds();

        return (exchange, chain) -> chain.filter(exchange).then(Mono.defer(() -> {
            // ─── Post-filter phase: evaluate conditions ─────────────────────
            int statusCode = exchange.getResponse().getStatusCode() != null
                    ? exchange.getResponse().getStatusCode().value()
                    : 0;

            if (!matchesTrigger(config.getTriggerOn(), statusCode)) {
                return Mono.empty();
            }

            if (config.getHeaderMatch() != null && !config.getHeaderMatch().isBlank()) {
                if (!matchesHeader(config.getHeaderMatch(), exchange)) {
                    return Mono.empty();
                }
            }

            // ─── Cooldown check ─────────────────────────────────────────────
            String routeId = extractRouteId(exchange);
            if (cooldownSeconds > 0 && routeId != null) {
                Instant last = lastNotification.get(routeId);
                if (last != null && Instant.now().isBefore(last.plusSeconds(cooldownSeconds))) {
                    log.debug("WEBHOOK_NOTIFY: cooldown active for route {} — skipping", routeId);
                    return Mono.empty();
                }
                lastNotification.put(routeId, Instant.now());
            }

            // ─── Build payload ──────────────────────────────────────────────
            String payload = buildPayload(exchange, config, routeId, statusCode);

            // ─── Fire-and-forget dispatch ───────────────────────────────────
            dispatchWebhook(webhookUrl, secret, payload);

            return Mono.empty();
        }));
    }

    // ─── Condition matching ───────────────────────────────────────────────────

    /**
     * Checks whether the response status matches the {@code triggerOn} condition.
     */
    static boolean matchesTrigger(String triggerOn, int statusCode) {
        if (triggerOn == null || triggerOn.isBlank() || "5xx".equalsIgnoreCase(triggerOn)) {
            return statusCode >= 500 && statusCode <= 599;
        }
        if ("4xx".equalsIgnoreCase(triggerOn)) {
            return statusCode >= 400 && statusCode <= 499;
        }
        if ("ALL".equalsIgnoreCase(triggerOn)) {
            return true;
        }
        // Comma-separated specific codes
        for (String code : COMMA_SPLIT.split(triggerOn)) {
            try {
                if (Integer.parseInt(code.trim()) == statusCode) {
                    return true;
                }
            } catch (NumberFormatException e) {
                log.warn("WEBHOOK_NOTIFY: invalid status code in triggerOn: '{}'", code);
            }
        }
        return false;
    }

    /**
     * Checks whether the request/response matches the {@code headerMatch} condition.
     * Format: {@code Header-Name=expectedValue}.
     */
    static boolean matchesHeader(String headerMatch, ServerWebExchange exchange) {
        int eq = headerMatch.indexOf('=');
        if (eq <= 0 || eq >= headerMatch.length() - 1) {
            return false;
        }
        String headerName = headerMatch.substring(0, eq).trim();
        String expectedValue = headerMatch.substring(eq + 1).trim();

        // Check request headers first, then response headers
        String reqVal = exchange.getRequest().getHeaders().getFirst(headerName);
        if (expectedValue.equals(reqVal)) {
            return true;
        }
        String resVal = exchange.getResponse().getHeaders().getFirst(headerName);
        return expectedValue.equals(resVal);
    }

    // ─── Payload building ─────────────────────────────────────────────────────

    /**
     * Builds the JSON webhook payload from the exchange.
     */
    private String buildPayload(ServerWebExchange exchange, Config config,
                                 String routeId, int statusCode) {
        ServerHttpRequest request = exchange.getRequest();
        Map<String, Object> payload = new LinkedHashMap<>();

        payload.put("routeId", routeId != null ? routeId : "unknown");
        payload.put("correlationId", request.getHeaders().getFirst(RoutifyHeaders.CORRELATION_ID));
        payload.put("method", request.getMethod() != null ? request.getMethod().name() : "");
        payload.put("path", request.getURI().getPath());
        if (config.isIncludeResponseStatus()) {
            payload.put("status", statusCode);
        }
        payload.put("timestamp", Instant.now().toString());

        // Include sanitized request headers if configured
        if (config.isIncludeRequestHeaders()) {
            Map<String, String> sanitized = new LinkedHashMap<>();
            request.getHeaders().forEach((name, values) -> {
                if (!REDACTED_HEADERS.contains(name.toLowerCase(Locale.ROOT)) && !values.isEmpty()) {
                    sanitized.put(name, values.getFirst());
                }
            });
            payload.put("headers", sanitized);
        }

        try {
            String json = OBJECT_MAPPER.writeValueAsString(payload);
            // Truncate to maxPayloadSize
            if (json.length() > config.getMaxPayloadSize()) {
                json = json.substring(0, config.getMaxPayloadSize());
            }
            return json;
        } catch (Exception e) {
            log.warn("WEBHOOK_NOTIFY: failed to serialize payload", e);
            return "{}";
        }
    }

    // ─── Webhook dispatch ─────────────────────────────────────────────────────

    /**
     * Fires the webhook POST asynchronously. Fire-and-forget: errors are logged
     * but never affect the client response.
     */
    private void dispatchWebhook(String webhookUrl, String secret, String payload) {
        var requestSpec = webClient.post()
                .uri(webhookUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload);

        // HMAC signing
        if (secret != null && !secret.isBlank()) {
            String signature = computeHmacSha256(payload, secret);
            if (signature != null) {
                requestSpec = requestSpec.header("X-Routify-Signature", "sha256=" + signature);
            }
        }

        requestSpec
                .retrieve()
                .toBodilessEntity()
                .timeout(WEBHOOK_TIMEOUT)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        _ -> log.debug("WEBHOOK_NOTIFY: webhook delivered to {}", webhookUrl),
                        err -> log.warn("WEBHOOK_NOTIFY: webhook delivery failed to {}: {}",
                                webhookUrl, err.getMessage())
                );
    }

    // ─── HMAC signing ─────────────────────────────────────────────────────────

    /**
     * Computes HMAC-SHA256 of the payload using the given secret.
     *
     * @return hex-encoded HMAC, or null if computation fails
     */
    static String computeHmacSha256(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            log.warn("WEBHOOK_NOTIFY: HMAC computation failed", e);
            return null;
        }
    }

    // ─── Route ID extraction ──────────────────────────────────────────────────

    private static String extractRouteId(ServerWebExchange exchange) {
        var route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route instanceof Route r) {
            String id = r.getId();
            if (id != null && id.contains("::")) {
                return id.substring(id.indexOf("::") + 2);
            }
            return id;
        }
        return null;
    }

    // ─── Config ───────────────────────────────────────────────────────────────

    @Data
    public static class Config {
        /** URL to POST the webhook notification to. Required. */
        private String webhookUrl = "";

        /** HMAC-SHA256 signing key for {@code X-Routify-Signature}. Optional. */
        private String secret = "";

        /**
         * Trigger condition: {@code 5xx}, {@code 4xx}, {@code ALL}, or
         * comma-separated specific status codes (e.g. {@code 503,504}).
         * Default: {@code 5xx}.
         */
        private String triggerOn = "5xx";

        /**
         * Optional header value match condition.
         * Format: {@code Header-Name=expectedValue}.
         * Only fires webhook when the specified header has the expected value.
         */
        private String headerMatch = "";

        /** Include sanitized request headers in the webhook payload. Default: false. */
        private boolean includeRequestHeaders = false;

        /** Include response status code in the webhook payload. Default: true. */
        private boolean includeResponseStatus = true;

        /** Maximum webhook payload size in bytes. Default: 4096. */
        private int maxPayloadSize = 4096;

        /**
         * Minimum seconds between consecutive webhooks for the same route.
         * Prevents notification storms under sustained high error rates.
         * Default: 10.
         */
        private long cooldownSeconds = 10;
    }
}

