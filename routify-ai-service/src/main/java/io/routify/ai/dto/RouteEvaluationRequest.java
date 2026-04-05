package io.routify.ai.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * Request payload sent by the API Gateway to {@code POST /api/v1/ai-filter/evaluate}.
 *
 * <p>Contains everything the AI service needs to construct a prompt and
 * reach a filtering verdict:
 * <ol>
 *   <li>Route identity (routeId, routeName, tenantId)</li>
 *   <li>The operator-defined AI filter config (policy, mode, thresholds, etc.)</li>
 *   <li>The live request context (method, path, headers, optional body snippet)</li>
 * </ol>
 *
 * <p>Sensitive headers (Authorization, Cookie, X-Api-Key) are redacted by the gateway
 * before this payload is built and must never appear in {@code requestContext.headers}.
 */
public record RouteEvaluationRequest(

        /** UUID of the route that triggered this evaluation. */
        @NotBlank String routeId,

        /** Human-readable route name — used in prompts for context. */
        @NotBlank String routeName,

        /** UUID of the tenant that owns the route. */
        @NotBlank String tenantId,

        /**
         * The AI filter configuration attached to the route.
         * Carries the natural-language policy and operational settings.
         */
        @NotNull @Valid AiFilterConfig filterConfig,

        /** Metadata about the intercepted HTTP request. */
        @NotNull @Valid RequestContext requestContext

) {

    /**
     * AI-specific filter configuration stored as JSONB in the
     * {@code filter_definition.config} column and forwarded here by the gateway.
     *
     * @param policyDescription     Natural-language rule the LLM must enforce.
     *                              E.g. "Block requests that contain SQL injection patterns."
     * @param evaluationMode        SYNC — block until verdict; ASYNC — fire-and-forget (audit only).
     * @param includeBody           Whether to include a body excerpt in the prompt.
     * @param maxBodyBytes          Maximum bytes of request body to include (default 512).
     * @param fallbackAction        Verdict to apply when the LLM is unavailable or times out.
     * @param confidenceThreshold   Minimum confidence (0.0–1.0) required to honour the verdict;
     *                              below this the {@code fallbackAction} is used instead.
     * @param cacheEnabled          Whether to use Redis verdict caching.
     * @param cacheTtlSeconds       Cache TTL in seconds (default 30).
     */
    public record AiFilterConfig(
            @NotBlank String policyDescription,
            String evaluationMode,
            boolean includeBody,
            int maxBodyBytes,
            String fallbackAction,
            double confidenceThreshold,
            boolean cacheEnabled,
            int cacheTtlSeconds
    ) {
        /** Canonical defaults applied when optional fields are absent. */
        public AiFilterConfig {
            if (evaluationMode == null || evaluationMode.isBlank()) evaluationMode = "SYNC";
            if (maxBodyBytes <= 0)       maxBodyBytes       = 512;
            if (fallbackAction == null || fallbackAction.isBlank()) fallbackAction = "ALLOW";
            if (confidenceThreshold <= 0) confidenceThreshold = 0.85;
            if (cacheTtlSeconds <= 0)    cacheTtlSeconds    = 30;
        }
    }

    /**
     * Metadata about the intercepted HTTP request.
     *
     * @param method        HTTP method (GET, POST, …).
     * @param path          Request path (e.g. /api/v1/orders).
     * @param queryString   Raw query string — may be null.
     * @param clientIp      Originating client IP (from X-Forwarded-For or remote address).
     * @param headers       Sanitised request headers — sensitive credentials MUST be
     *                      removed by the gateway before populating this map.
     * @param bodyExcerpt   Base64-encoded body prefix up to {@code maxBodyBytes}.
     *                      {@code null} when {@code includeBody=false} or body is absent.
     * @param userContext   Optional auth context propagated from the gateway
     *                      (userId, tenantId, role) — may be {@code null} for unauthenticated requests.
     */
    public record RequestContext(
            @NotBlank String method,
            @NotBlank String path,
            String queryString,
            String clientIp,
            Map<String, String> headers,
            String bodyExcerpt,
            UserContext userContext
    ) {}

    /**
     * Authenticated user context forwarded by the gateway after JWT / API-key validation.
     * Useful for user-scoped policy rules (e.g. "block requests from suspended users").
     */
    public record UserContext(
            String userId,
            String tenantId,
            String role,
            String email
    ) {}
}

