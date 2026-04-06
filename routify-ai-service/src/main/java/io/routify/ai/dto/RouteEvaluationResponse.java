package io.routify.ai.dto;

/**
 * The AI service's verdict on a single route request evaluation.
 *
 * <p>Returned by {@code POST /api/v1/ai-filter/evaluate} and consumed by the gateway
 * to decide whether to allow, block, or flag the intercepted request.
 *
 * @param action        The enforcement decision — one of {@code ALLOW}, {@code BLOCK}, {@code FLAG}.
 *                      <ul>
 *                        <li>{@code ALLOW} — let the request through.</li>
 *                        <li>{@code BLOCK} — reject with HTTP 403 Forbidden.</li>
 *                        <li>{@code FLAG}  — allow, but tag the request with
 *                            {@code X-AI-Filter-Flag: true} for downstream inspection.</li>
 *                      </ul>
 * @param actionType    Mirror of {@code action} as a String (gateway convenience field).
 * @param reason        One-sentence human-readable explanation from the LLM.
 *                      Included in the 403 body so operators can understand the decision.
 * @param confidence    LLM confidence score in [0.0, 1.0].
 *                      Verdicts below the configured {@code confidenceThreshold} are
 *                      overridden by {@code fallbackAction} in the gateway.
 * @param isAllowed     Convenience boolean: {@code true} when action is ALLOW or FLAG.
 * @param cached        {@code true} when this verdict was served from the Redis cache
 *                      (no LLM call was made).
 * @param latencyMs     Wall-clock time in milliseconds from request receipt to verdict.
 *                      Includes cache lookup; excludes the Redis write for non-cached verdicts.
 * @param evaluationId  Unique evaluation trace ID — matches the Kafka event published to
 *                      {@code routify.ai.filter.decisions} for audit correlation.
 */
public record RouteEvaluationResponse(
        VerdictAction action,
        String        actionType,
        String        reason,
        double        confidence,
        boolean       isAllowed,
        boolean       cached,
        long          latencyMs,
        String        evaluationId
) {

    /**
     * Possible enforcement actions returned by the AI filter.
     */
    public enum VerdictAction {
        /** Allow the request to proceed to the upstream service. */
        ALLOW,
        /**
         * Block the request — the gateway returns {@code 403 Forbidden} with
         * a Problem JSON body containing the {@code reason}.
         */
        BLOCK,
        /**
         * Allow the request but mark it with {@code X-AI-Filter-Flag: true}.
         * Useful for monitoring / shadow mode before enabling hard blocking.
         */
        FLAG
    }

    // ─── Static factory helpers ───────────────────────────────────────────────

    public static RouteEvaluationResponse allow(String reason, double confidence,
                                                boolean cached, long latencyMs, String evalId) {
        return new RouteEvaluationResponse(
                VerdictAction.ALLOW, "ALLOW", reason, confidence, true, cached, latencyMs, evalId);
    }

    public static RouteEvaluationResponse block(String reason, double confidence,
                                                boolean cached, long latencyMs, String evalId) {
        return new RouteEvaluationResponse(
                VerdictAction.BLOCK, "BLOCK", reason, confidence, false, cached, latencyMs, evalId);
    }

    public static RouteEvaluationResponse flag(String reason, double confidence,
                                               boolean cached, long latencyMs, String evalId) {
        return new RouteEvaluationResponse(
                VerdictAction.FLAG, "FLAG", reason, confidence, true, cached, latencyMs, evalId);
    }

    /**
     * Fallback verdict — used when the LLM is unavailable or the circuit breaker is open.
     * The {@code action} reflects the configured {@code fallbackAction} from {@link RouteEvaluationRequest.AiFilterConfig}.
     */
    public static RouteEvaluationResponse fallback(String fallbackAction, String reason, long latencyMs) {
        VerdictAction action = parseFallback(fallbackAction);
        return new RouteEvaluationResponse(
                action, fallbackAction, reason, 0.0,
                action != VerdictAction.BLOCK, false, latencyMs, "fallback");
    }

    private static VerdictAction parseFallback(String raw) {
        if (raw == null) return VerdictAction.ALLOW;
        return switch (raw.toUpperCase()) {
            case "BLOCK" -> VerdictAction.BLOCK;
            case "FLAG"  -> VerdictAction.FLAG;
            default      -> VerdictAction.ALLOW;
        };
    }
}

