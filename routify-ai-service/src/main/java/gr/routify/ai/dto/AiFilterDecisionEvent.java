package gr.routify.ai.dto;

/**
 * @deprecated Moved to {@link gr.routify.common.event.AiFilterDecisionEvent} in routify-common
 * so that routify-audit-service can consume it without a circular module dependency.
 * This type alias is kept for backward compatibility — remove in next major release.
 */
@Deprecated(since = "1.0.1", forRemoval = true)
public record AiFilterDecisionEvent(
        String  evaluationId,
        String  routeId,
        String  routeName,
        String  tenantId,
        String  action,
        String  reason,
        double  confidence,
        boolean cached,
        String  evaluationMode,
        long    latencyMs,
        String  method,
        String  path,
        String  clientIp,
        java.time.Instant evaluatedAt
) {
    /** Converts to the canonical common type. */
    public gr.routify.common.event.AiFilterDecisionEvent toCommon() {
        return new gr.routify.common.event.AiFilterDecisionEvent(
                evaluationId, routeId, routeName, tenantId, action, reason,
                confidence, cached, evaluationMode, latencyMs, method, path, clientIp, evaluatedAt);
    }
}
