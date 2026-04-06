package io.routify.ai.dto;

import java.util.Map;

/**
 * The AI modification service's response for a single request mutation evaluation.
 *
 * <p>Returned by {@code POST /api/v1/ai-modifier/evaluate} and consumed by
 * {@link io.routify.gateway.filter.AiModifierGatewayFilterFactory} to decide
 * whether to replace the outgoing request's headers and/or body before routing downstream.
 *
 * <h3>Gateway re-injection contract</h3>
 * When {@code mutationApplied=true}, the gateway MUST:
 * <ol>
 *   <li>Replace request headers with any entries in {@code mutatedHeaders} (merge, not replace-all).</li>
 *   <li>Replace the request body with {@code mutatedBody} if non-null.</li>
 *   <li>Inject {@code X-AI-Modifier-Applied: true} and {@code X-AI-Modifier-Id: {mutationId}}
 *       into the downstream request for observability.</li>
 * </ol>
 *
 * @param mutationId      Unique evaluation trace ID.
 * @param mutationApplied Whether the LLM produced a valid mutation (false = passthrough).
 * @param mutationType    The type of mutation applied: PII_SCRUB | TRANSLATE |
 *                        HEADER_REWRITE | CUSTOM | PASSTHROUGH.
 * @param mutatedHeaders  Headers to replace/add on the request. Empty when no header changes.
 *                        Keys are header names; values are new header values.
 * @param mutatedBody     Replacement request body string. {@code null} when body was not
 *                        mutated or when {@code mutationApplied=false}.
 * @param reason          One-sentence LLM explanation of what was changed and why.
 * @param cached          Whether this result was served from the Redis cache.
 * @param latencyMs       Total evaluation latency in milliseconds.
 */
public record AiModificationResponse(
        String              mutationId,
        boolean             mutationApplied,
        MutationType        mutationType,
        Map<String, String> mutatedHeaders,
        String              mutatedBody,
        String              reason,
        boolean             cached,
        long                latencyMs
) {

    /**
     * The type of mutation the LLM applied.
     */
    public enum MutationType {
        /** Personally-identifiable information was scrubbed from the body and/or headers. */
        PII_SCRUB,
        /** The body content was translated (language translation or format conversion). */
        TRANSLATE,
        /** One or more request headers were rewritten. */
        HEADER_REWRITE,
        /** A custom operator-defined mutation was applied. */
        CUSTOM,
        /** No mutation was applied — request passed through unchanged. */
        PASSTHROUGH
    }

    // ─── Static factory helpers ───────────────────────────────────────────────

    /**
     * Passthrough verdict — used when:
     * <ul>
     *   <li>The LLM is unavailable or the circuit breaker is open.</li>
     *   <li>The LLM response failed JSON validation.</li>
     *   <li>The LLM determined no mutation was needed.</li>
     * </ul>
     */
    public static AiModificationResponse passthrough(String reason, long latencyMs, String mutationId) {
        return new AiModificationResponse(
                mutationId, false, MutationType.PASSTHROUGH,
                Map.of(), null, reason, false, latencyMs);
    }

    /** Cached passthrough (cache hit that was a previous passthrough result). */
    public static AiModificationResponse cachedPassthrough(String reason, long latencyMs, String mutationId) {
        return new AiModificationResponse(
                mutationId, false, MutationType.PASSTHROUGH,
                Map.of(), null, reason, true, latencyMs);
    }
}

