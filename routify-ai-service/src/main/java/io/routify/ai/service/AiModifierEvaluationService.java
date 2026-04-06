package io.routify.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.routify.ai.dto.AiModificationRequest;
import io.routify.ai.dto.AiModificationResponse;
import io.routify.ai.dto.AiModificationResponse.MutationType;
import io.routify.ai.metrics.AiModifierMetrics;
import io.routify.ai.prompt.AiModifierPromptBuilderService;
import io.routify.common.event.AiModificationDecisionEvent;
import io.routify.common.event.KafkaTopics;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Core AI Modification Filter evaluation service.
 *
 * <h3>Mutation flow</h3>
 * <pre>
 * 1. Check Redis mutation cache  → cache HIT: return immediately (sub-5ms p99)
 * 2. Build prompt (system + user) via AiModifierPromptBuilderService
 * 3. Call LLM via Spring AI ChatClient (wrapped in Resilience4j circuit breaker)
 * 4. Parse and VALIDATE structured JSON response → AiModificationResponse
 * 5. Structural safety checks (body is valid JSON if original was JSON, size cap, etc.)
 * 6. Write result to Redis cache (if cacheEnabled)
 * 7. Publish AiModificationDecisionEvent to Kafka (async, fire-and-forget)
 * 8. Return mutation result to gateway
 * </pre>
 *
 * <h3>Security considerations</h3>
 * The LLM output is directly injected back into the live request stream, making this
 * service a higher-security surface than {@link AiFilterEvaluationService}.
 * Additional validation steps are applied:
 * <ul>
 *   <li>Mutated body must not exceed {@code maxBodyBytes * 2} bytes (explosion guard).</li>
 *   <li>If the original body was valid JSON, the mutated body must also be valid JSON.</li>
 *   <li>Content-Type is preserved — the service never changes the Content-Type header.</li>
 *   <li>On any validation failure, passthrough is applied — never injection of invalid data.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiModifierEvaluationService {

    private static final String CIRCUIT_BREAKER_NAME  = "aiModifierLlm";
    /** Exchange attribute key where the gateway caches the raw request body bytes. */
    public  static final String CACHE_KEY_PREFIX       = "routify:ai:mutation:";

    private final ChatClient                    chatClient;
    private final AiModifierPromptBuilderService promptBuilder;
    private final MutationCacheService          mutationCache;
    private final AiModifierMetrics             metrics;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper                  objectMapper;

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Evaluates a route request for mutation.
     *
     * @param request the full modification request from the gateway
     * @return a non-null mutation result (never throws — falls back to passthrough on any failure)
     */
    public AiModificationResponse evaluate(AiModificationRequest request) {
        long startMs = System.currentTimeMillis();

        // ── 1. Cache lookup ───────────────────────────────────────────────────
        Optional<AiModificationResponse> cached = mutationCache.get(request);
        if (cached.isPresent()) {
            AiModificationResponse hit = cached.get();
            metrics.recordCacheHit(request.routeId());
            metrics.recordEvaluation(
                    hit.mutationType().name(), request.routeId(),
                    true, hit.mutationApplied(),
                    System.currentTimeMillis() - startMs);
            return hit;
        }

        // ── 2–6. LLM call (wrapped in circuit breaker) ────────────────────────
        AiModificationResponse result = callLlmWithCircuitBreaker(request, startMs);

        // ── 7. Publish Kafka event (fire-and-forget) ──────────────────────────
        publishDecisionEventAsync(request, result);

        return result;
    }

    // ─── LLM interaction ─────────────────────────────────────────────────────

    @CircuitBreaker(name = CIRCUIT_BREAKER_NAME, fallbackMethod = "llmFallback")
    AiModificationResponse callLlmWithCircuitBreaker(AiModificationRequest request, long startMs) {
        return callLlm(request, startMs);
    }

    @SuppressWarnings("unused") // invoked by Resilience4j via reflection
    AiModificationResponse llmFallback(AiModificationRequest request, long startMs, Throwable t) {
        long latency = System.currentTimeMillis() - startMs;
        log.warn("AI modifier circuit breaker fallback: route={} cause={}",
                request.routeId(), t.getClass().getSimpleName());

        metrics.recordFallback(request.routeId());
        String mutationId = UUID.randomUUID().toString();
        AiModificationResponse result = AiModificationResponse.passthrough(
                "LLM unavailable — passthrough applied (%s)".formatted(t.getClass().getSimpleName()),
                latency, mutationId);
        publishDecisionEventAsync(request, result);
        return result;
    }

    private AiModificationResponse callLlm(AiModificationRequest request, long startMs) {
        String systemPrompt = promptBuilder.buildSystemPrompt();
        String userPrompt   = promptBuilder.buildUserPrompt(request);

        log.debug("Calling LLM for mutation: route={} targets={} includeBody={}",
                request.routeId(),
                request.modifierConfig().targetFields(),
                request.modifierConfig().includeBody());

        // temperature(0.1) allows slight variance for creative transformations
        // (translation, rephrasing) while keeping the output schema deterministic
        double temperature = request.modifierConfig().temperature();
        int    maxTokens   = request.modifierConfig().maxTokens();

        String rawResponse = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .options(ChatOptions.builder()
                        .temperature(temperature)
                        .maxTokens(maxTokens)
                        .build())
                .call()
                .content();

        long latency = System.currentTimeMillis() - startMs;

        // Parse + validate the mutation output
        AiModificationResponse result = parseAndValidate(rawResponse, request, latency);

        // Write to Redis cache
        mutationCache.put(request, result);

        metrics.recordEvaluation(
                result.mutationType().name(), request.routeId(),
                false, result.mutationApplied(), latency);

        log.info("AI modifier result: route={} applied={} type={} latency={}ms cached=false",
                request.routeId(), result.mutationApplied(), result.mutationType(), latency);

        return result;
    }

    // ─── Response parsing & validation ───────────────────────────────────────

    /**
     * Parses the raw LLM response and applies security validation before allowing
     * the mutation to be re-injected into the request stream.
     *
     * <h3>Validation steps</h3>
     * <ol>
     *   <li>Extract the first JSON object block (handles markdown fences, leading text).</li>
     *   <li>Validate required fields: mutatedHeaders, reason, applied.</li>
     *   <li>If body was mutated and original was valid JSON, mutated body must be valid JSON.</li>
     *   <li>Mutated body size must not exceed {@code maxBodyBytes * 2}.</li>
     *   <li>Content-Type header must not be changed.</li>
     * </ol>
     */
    private AiModificationResponse parseAndValidate(String rawResponse,
                                                    AiModificationRequest request,
                                                    long latencyMs) {
        String mutationId = UUID.randomUUID().toString();
        AiModificationRequest.AiModifierConfig cfg = request.modifierConfig();

        try {
            String json = extractJson(rawResponse);
            JsonNode node = objectMapper.readTree(json);

            boolean applied = node.path("applied").asBoolean(false);
            String  reason  = node.path("reason").asText("No reason provided");

            // Extract mutated headers (empty object = no changes)
            Map<String, String> mutatedHeaders = new HashMap<>();
            JsonNode headersNode = node.path("mutatedHeaders");
            if (headersNode.isObject()) {
                headersNode.fields().forEachRemaining(e -> {
                    // Never allow Content-Type to be changed by the LLM — security measure
                    if (!"content-type".equalsIgnoreCase(e.getKey())) {
                        mutatedHeaders.put(e.getKey(), e.getValue().asText());
                    }
                });
            }

            // Extract mutated body (null = no body changes)
            String mutatedBody = null;
            JsonNode bodyNode = node.path("mutatedBody");
            if (!bodyNode.isNull() && !bodyNode.isMissingNode()) {
                mutatedBody = bodyNode.asText();
            }

            // ── Safety check 1: Body size explosion guard ─────────────────────
            if (mutatedBody != null) {
                int maxAllowed = cfg.maxBodyBytes() * 2;
                if (mutatedBody.getBytes(StandardCharsets.UTF_8).length > maxAllowed) {
                    log.warn("AI modifier mutated body exceeds size cap ({} > {}) — passthrough applied. route={}",
                            mutatedBody.length(), maxAllowed, request.routeId());
                    metrics.recordParseFailure();
                    return AiModificationResponse.passthrough(
                            "Mutated body exceeds size cap — passthrough applied", latencyMs, mutationId);
                }
            }

            // ── Safety check 2: JSON structural validity ─────────────────────
            if (mutatedBody != null && isJsonBody(request)) {
                try {
                    objectMapper.readTree(mutatedBody);
                } catch (Exception e) {
                    log.warn("AI modifier mutated body is not valid JSON — passthrough applied. route={}", request.routeId());
                    metrics.recordParseFailure();
                    return AiModificationResponse.passthrough(
                            "Mutated body is not valid JSON — passthrough applied", latencyMs, mutationId);
                }
            }

            // ── Infer mutation type from what was changed ─────────────────────
            MutationType mutationType = inferMutationType(cfg.modificationPrompt(), applied,
                    mutatedBody != null, !mutatedHeaders.isEmpty());

            if (!applied || (mutatedHeaders.isEmpty() && mutatedBody == null)) {
                // LLM determined no mutation was needed
                return new AiModificationResponse(
                        mutationId, false, MutationType.PASSTHROUGH,
                        Map.of(), null, reason, false, latencyMs);
            }

            return new AiModificationResponse(
                    mutationId, true, mutationType,
                    mutatedHeaders, mutatedBody, reason, false, latencyMs);

        } catch (Exception e) {
            log.error("Failed to parse AI modifier LLM response — passthrough. Raw='{}' error={}",
                    rawResponse, e.getMessage());
            metrics.recordParseFailure();
            return AiModificationResponse.passthrough(
                    "LLM response parse failure — passthrough applied", latencyMs, mutationId);
        }
    }

    /**
     * Infers the mutation type from the operator's instruction and what was actually changed.
     * Used for metrics tagging and audit logging.
     */
    private MutationType inferMutationType(String prompt, boolean applied,
                                           boolean bodyChanged, boolean headersChanged) {
        if (!applied) return MutationType.PASSTHROUGH;

        String lower = prompt != null ? prompt.toLowerCase() : "";
        if (lower.contains("pii") || lower.contains("email") || lower.contains("ssn")
                || lower.contains("scrub") || lower.contains("redact") || lower.contains("mask")) {
            return MutationType.PII_SCRUB;
        }
        if (lower.contains("translat") || lower.contains("language")) {
            return MutationType.TRANSLATE;
        }
        if (headersChanged && !bodyChanged) {
            return MutationType.HEADER_REWRITE;
        }
        return MutationType.CUSTOM;
    }

    /** Returns true if the request's Content-Type suggests a JSON body. */
    private boolean isJsonBody(AiModificationRequest request) {
        if (request.requestContext().headers() == null) return false;
        return request.requestContext().headers().entrySet().stream()
                .filter(e -> "content-type".equalsIgnoreCase(e.getKey()))
                .anyMatch(e -> e.getValue() != null && e.getValue().contains("application/json"));
    }

    private String extractJson(String raw) {
        if (raw == null) return "{}";
        int start = raw.indexOf('{');
        int end   = raw.lastIndexOf('}');
        if (start == -1 || end == -1 || end < start) return "{}";
        return raw.substring(start, end + 1);
    }

    // ─── Telemetry ────────────────────────────────────────────────────────────

    @Async("aiFilterExecutor")
    void publishDecisionEventAsync(AiModificationRequest request, AiModificationResponse result) {
        try {
            String originalBodyHash = hashBody(decodeBody(request.requestContext().bodyBase64()));
            String mutatedBodyHash  = hashBody(result.mutatedBody() != null
                    ? result.mutatedBody().getBytes(StandardCharsets.UTF_8) : null);

            List<String> headersModified = result.mutatedHeaders() != null
                    ? List.copyOf(result.mutatedHeaders().keySet()) : List.of();

            var event = new AiModificationDecisionEvent(
                    result.mutationId(),
                    request.routeId(),
                    request.routeName(),
                    request.tenantId(),
                    result.mutationApplied(),
                    result.mutationType().name(),
                    truncateReason(result.reason()),
                    originalBodyHash,
                    mutatedBodyHash,
                    headersModified,
                    result.cached(),
                    result.latencyMs(),
                    request.requestContext().method(),
                    request.requestContext().path(),
                    request.requestContext().clientIp(),
                    Instant.now()
            );
            kafkaTemplate.send(KafkaTopics.AI_MODIFICATION_EVENTS, request.routeId(), event);
        } catch (Exception e) {
            log.warn("Failed to publish AI modification decision event: routeId={} error={}",
                    request.routeId(), e.getMessage());
        }
    }

    private byte[] decodeBody(String base64) {
        if (base64 == null || base64.isBlank()) return null;
        try {
            // Strip any " (base64-encoded...)" suffix added by the prompt builder
            String clean = base64.split(" \\(")[0].trim();
            return Base64.getDecoder().decode(clean);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String hashBody(byte[] body) {
        if (body == null) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String truncateReason(String reason) {
        if (reason == null) return null;
        String clean = reason.replaceAll("[\\x00-\\x1F\\x7F]", " ").trim();
        return clean.length() > 500 ? clean.substring(0, 500) : clean;
    }
}

