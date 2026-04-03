package gr.routify.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.ai.dto.RouteEvaluationRequest;
import gr.routify.ai.dto.RouteEvaluationResponse;
import gr.routify.ai.dto.RouteEvaluationResponse.VerdictAction;
import gr.routify.ai.metrics.AiFilterMetrics;
import gr.routify.ai.prompt.PromptBuilderService;
import gr.routify.common.event.AiFilterDecisionEvent;
import gr.routify.common.event.KafkaTopics;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Core AI filter evaluation service.
 *
 * <h3>Evaluation flow (SYNC mode)</h3>
 * <pre>
 * 1. Check Redis verdict cache  → cache HIT: return immediately (sub-5ms p99)
 * 2. Build prompt (system + user) via PromptBuilderService
 * 3. Call LLM via Spring AI ChatClient (wrapped in Resilience4j circuit breaker)
 * 4. Parse structured JSON response → RouteEvaluationResponse
 * 5. Validate confidence threshold — below threshold → apply fallbackAction
 * 6. Write verdict to Redis cache
 * 7. Publish AiFilterDecisionEvent to Kafka (async, non-blocking)
 * 8. Return verdict to gateway
 * </pre>
 *
 * <h3>ASYNC mode</h3>
 * The gateway calls this service from a {@code @Async} thread and always proceeds
 * with ALLOW regardless of the verdict. The verdict is published to Kafka for
 * post-hoc audit/flagging only.
 *
 * <h3>Latency budget</h3>
 * <ul>
 *   <li>Cache hit: &lt;5ms p99</li>
 *   <li>Cache miss (OpenAI gpt-4o-mini): ~300–800ms p50, ~2s p99</li>
 *   <li>Hard timeout (Resilience4j TimeLimiter): 3s → fallback verdict</li>
 *   <li>Circuit open: &lt;1ms → fallback verdict</li>
 * </ul>
 *
 * <h3>Structured JSON output enforcement</h3>
 * The system prompt mandates a JSON-only response. We additionally:
 * <ul>
 *   <li>Parse the LLM response with Jackson and validate all required fields.</li>
 *   <li>On any parse failure, return the configured fallbackAction rather than crashing.</li>
 *   <li>Check confidence against the configured threshold; low-confidence verdicts also
 *       fall back to the safe default.</li>
 * </ul>
 *
 * <h3>Provider swapping</h3>
 * The {@link ChatClient} is injected as a Spring bean (configured in
 * {@link gr.routify.ai.config.AiServiceConfig}). Swapping providers (OpenAI → Ollama →
 * Anthropic) only requires changing the starter dependency and {@code spring.ai.*} config —
 * this class has no provider-specific code.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiFilterEvaluationService {

    private static final String CIRCUIT_BREAKER_NAME = "aiFilterLlm";

    private final ChatClient          chatClient;
    private final PromptBuilderService promptBuilder;
    private final VerdictCacheService verdictCache;
    private final AiFilterMetrics     metrics;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper        objectMapper;

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Evaluates a route request against the configured AI filter policy.
     *
     * <p>This is the main entry point called by the gateway filter.
     * SYNC mode: the gateway awaits the verdict before forwarding the request.
     * ASYNC mode: the gateway fires this off and proceeds — use {@link #evaluateAsync}.
     *
     * @param request the full evaluation request
     * @return a non-null verdict (never throws — falls back on any failure)
     */
    public RouteEvaluationResponse evaluate(RouteEvaluationRequest request) {
        long startMs = System.currentTimeMillis();

        // ── 1. Cache lookup ───────────────────────────────────────────────────
        var cached = verdictCache.get(request);
        if (cached.isPresent()) {
            metrics.recordEvaluation(cached.get().action().name(),
                    request.filterConfig().evaluationMode(), true,
                    System.currentTimeMillis() - startMs);
            return cached.get();
        }

        // ── 2–6. LLM call (wrapped in circuit breaker) ────────────────────────
        RouteEvaluationResponse verdict = callLlmWithCircuitBreaker(request, startMs);

        // ── 7. Publish telemetry event asynchronously (fire-and-forget) ───────
        publishDecisionEventAsync(request, verdict);

        return verdict;
    }

    /**
     * Asynchronous evaluation — returns a CompletableFuture that completes with
     * the verdict. Used in ASYNC evaluation mode: the gateway does NOT await this.
     *
     * <p>Performance note: Spring's {@code @Async} runs this on a dedicated
     * bounded thread pool (configured in {@link gr.routify.ai.config.AiServiceConfig}),
     * keeping the gateway's request thread free.
     */
    @Async("aiFilterExecutor")
    public CompletableFuture<RouteEvaluationResponse> evaluateAsync(RouteEvaluationRequest request) {
        return CompletableFuture.completedFuture(evaluate(request));
    }

    // ─── LLM interaction ─────────────────────────────────────────────────────

    /**
     * Calls the LLM wrapped in a Resilience4j circuit breaker.
     * On circuit open or any exception, falls back to the configured fallback action.
     */
    @CircuitBreaker(name = CIRCUIT_BREAKER_NAME, fallbackMethod = "llmFallback")
    RouteEvaluationResponse callLlmWithCircuitBreaker(RouteEvaluationRequest request, long startMs) {
        return callLlm(request, startMs);
    }

    /**
     * Circuit breaker fallback — invoked when:
     * <ul>
     *   <li>The circuit is open (LLM consistently failing)</li>
     *   <li>A {@code TimeoutException} fires (exceeds 3s TimeLimiter)</li>
     *   <li>Any other uncaught exception from {@link #callLlm}</li>
     * </ul>
     *
     * <p>Always returns the operator-configured {@code fallbackAction} (default: ALLOW).
     * This guarantees the gateway can always serve requests even when the LLM is down.
     */
    @SuppressWarnings("unused") // invoked by Resilience4j via reflection
    RouteEvaluationResponse llmFallback(RouteEvaluationRequest request, long startMs, Throwable t) {
        long latency = System.currentTimeMillis() - startMs;
        log.warn("AI filter circuit breaker fallback: route={} cause={} fallbackAction={}",
                request.routeId(), t.getClass().getSimpleName(),
                request.filterConfig().fallbackAction());

        metrics.recordFallback(request.routeId());
        var verdict = RouteEvaluationResponse.fallback(
                request.filterConfig().fallbackAction(),
                "LLM unavailable — applying fallback policy (%s)".formatted(t.getClass().getSimpleName()),
                latency);
        publishDecisionEventAsync(request, verdict);
        return verdict;
    }

    /**
     * Performs the actual LLM call via Spring AI's {@link ChatClient}.
     *
     * <p>The ChatClient abstraction is provider-agnostic — the same code works
     * with OpenAI, Ollama, Anthropic, or any other supported ChatModel.
     */
    private RouteEvaluationResponse callLlm(RouteEvaluationRequest request, long startMs) {
        String systemPrompt = promptBuilder.buildSystemPrompt();
        String userPrompt   = promptBuilder.buildUserPrompt(request);

        log.debug("Calling LLM: route={} mode={} includeBody={}",
                request.routeId(),
                request.filterConfig().evaluationMode(),
                request.filterConfig().includeBody());

        // ── 3. Call Spring AI ChatClient ──────────────────────────────────────
        //
        // temperature(0.0f) is set at the ChatOptions level for maximum determinism.
        // maxTokens(256) caps response length — the LLM only needs to output a tiny JSON object.
        //
        // If you want to use the Bean-level defaults only (set in AiServiceConfig), remove
        // the .options(...) call below. The per-call override is kept for explicitness.
        String rawResponse = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .options(ChatOptions.builder()
                        .temperature(0.0)
                        .maxTokens(256)
                        .build())
                .call()
                .content();

        long latency = System.currentTimeMillis() - startMs;

        // ── 4–5. Parse + validate confidence ─────────────────────────────────
        RouteEvaluationResponse verdict = parseAndValidate(
                rawResponse, request.filterConfig(), latency);

        // ── 6. Write to Redis cache ───────────────────────────────────────────
        verdictCache.put(request, verdict);

        metrics.recordEvaluation(verdict.action().name(),
                request.filterConfig().evaluationMode(), false, latency);

        log.info("AI filter verdict: route={} action={} confidence={} latency={}ms cached=false",
                request.routeId(), verdict.action(), verdict.confidence(), latency);

        return verdict;
    }

    // ─── Response parsing ─────────────────────────────────────────────────────

    /**
     * Parses the raw LLM response string into a {@link RouteEvaluationResponse}.
     *
     * <h3>JSON extraction</h3>
     * The system prompt mandates pure JSON output, but some models may still add
     * whitespace, BOM characters, or a markdown code fence. We extract the first
     * {@code {...}} block defensively.
     *
     * <h3>Confidence threshold enforcement</h3>
     * If the LLM's confidence is below the configured threshold the verdict is
     * overridden with the configured {@code fallbackAction}. This prevents
     * low-certainty BLOCK verdicts from incorrectly rejecting legitimate traffic.
     */
    private RouteEvaluationResponse parseAndValidate(String rawResponse,
                                                     RouteEvaluationRequest.AiFilterConfig cfg,
                                                     long latencyMs) {
        String evalId = UUID.randomUUID().toString();

        try {
            // Extract the first JSON object from the response (defensive)
            String json = extractJson(rawResponse);
            JsonNode node = objectMapper.readTree(json);

            String actionRaw  = node.path("action").asText("ALLOW").toUpperCase();
            String reason     = node.path("reason").asText("No reason provided");
            double confidence = node.path("confidence").asDouble(0.5);

            // Unknown action values fall back to ALLOW
            VerdictAction action;
            try {
                action = VerdictAction.valueOf(actionRaw);
            } catch (IllegalArgumentException e) {
                log.warn("LLM returned unknown action '{}' — defaulting to ALLOW", actionRaw);
                action = VerdictAction.ALLOW;
            }

            // Confidence below threshold → override with fallback action
            if (confidence < cfg.confidenceThreshold()) {
                log.info("LLM confidence {} below threshold {} — applying fallback action {}",
                        confidence, cfg.confidenceThreshold(), cfg.fallbackAction());
                return RouteEvaluationResponse.fallback(
                        cfg.fallbackAction(),
                        "Confidence %.2f below threshold %.2f — fallback applied. Original LLM reason: %s"
                                .formatted(confidence, cfg.confidenceThreshold(), reason),
                        latencyMs);
            }

            return switch (action) {
                case ALLOW -> RouteEvaluationResponse.allow(reason, confidence, false, latencyMs, evalId);
                case BLOCK -> RouteEvaluationResponse.block(reason, confidence, false, latencyMs, evalId);
                case FLAG  -> RouteEvaluationResponse.flag(reason, confidence, false, latencyMs, evalId);
            };

        } catch (Exception e) {
            log.error("Failed to parse LLM response — applying fallback. Raw='{}'  error={}",
                    rawResponse, e.getMessage());
            metrics.recordParseError();
            return RouteEvaluationResponse.fallback(
                    cfg.fallbackAction(),
                    "LLM response parse failure — fallback applied",
                    latencyMs);
        }
    }

    /**
     * Defensively extracts the first JSON object block from a string.
     * Handles cases where the LLM adds markdown code fences or leading text.
     */
    private String extractJson(String raw) {
        if (raw == null) return "{}";
        int start = raw.indexOf('{');
        int end   = raw.lastIndexOf('}');
        if (start == -1 || end == -1 || end < start) {
            return "{}";
        }
        return raw.substring(start, end + 1);
    }

    // ─── Telemetry ────────────────────────────────────────────────────────────

    /**
     * Publishes an {@link AiFilterDecisionEvent} to Kafka asynchronously.
     * This is fire-and-forget — Kafka failures must never block the verdict response.
     */
    @Async("aiFilterExecutor")
    void publishDecisionEventAsync(RouteEvaluationRequest request, RouteEvaluationResponse verdict) {
        try {
            var event = new AiFilterDecisionEvent(
                    verdict.evaluationId(),
                    request.routeId(),
                    request.routeName(),
                    request.tenantId(),
                    verdict.action().name(),
                    verdict.reason(),
                    verdict.confidence(),
                    verdict.cached(),
                    request.filterConfig().evaluationMode(),
                    verdict.latencyMs(),
                    request.requestContext().method(),
                    request.requestContext().path(),
                    request.requestContext().clientIp(),
                    Instant.now()
            );
            kafkaTemplate.send(KafkaTopics.AI_FILTER_DECISIONS, request.routeId(), event);
        } catch (Exception e) {
            log.warn("Failed to publish AI filter decision event: routeId={} error={}",
                    request.routeId(), e.getMessage());
        }
    }
}

