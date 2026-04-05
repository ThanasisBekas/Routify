package io.routify.ai.dto;

import io.routify.ai.service.AiModifierEvaluationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * Request payload for the AI Modification Filter evaluation endpoint.
 *
 * <p>Sent by the API Gateway to {@code POST /api/v1/ai-modifier/evaluate} and
 * consumed by {@link AiModifierEvaluationService}.
 *
 * <p>Unlike {@link RouteEvaluationRequest} (which carries a policy to evaluate),
 * this request carries a <em>mutation instruction</em> — the LLM is instructed
 * to rewrite the request rather than to produce a verdict.
 */
public record AiModificationRequest(

        /** UUID of the route that triggered this mutation. */
        @NotBlank String routeId,

        /** Human-readable route name — used in prompts for context. */
        @NotBlank String routeName,

        /** UUID of the tenant that owns the route. */
        @NotBlank String tenantId,

        /** The AI modifier configuration attached to the route. */
        @NotNull @Valid AiModifierConfig modifierConfig,

        /** Metadata about the intercepted HTTP request. */
        @NotNull @Valid RequestContext requestContext

) {

    /**
     * AI modifier configuration stored as JSONB in {@code filter_definition.config}.
     *
     * @param modificationPrompt  Natural-language mutation instruction.
     *                            E.g. "Scrub all email addresses from the JSON body."
     * @param targetFields        Comma-separated targets: BODY, HEADERS, or BODY,HEADERS.
     * @param modelId             Optional LLM model override. Null = service default.
     * @param temperature         LLM temperature (0.0–1.0). Default 0.1.
     * @param maxTokens           Max tokens in LLM response. Default 1024.
     * @param fallbackBehavior    PASSTHROUGH (default) or BLOCK on LLM failure.
     * @param includeBody         Whether to include the full body in the prompt.
     * @param maxBodyBytes        Max bytes of body to include (default 2048).
     * @param cacheEnabled        Whether to use Redis mutation caching (default false).
     * @param cacheTtlSeconds     Cache TTL in seconds (default 60).
     */
    public record AiModifierConfig(
            @NotBlank String modificationPrompt,
            String  targetFields,
            String  modelId,
            double  temperature,
            int     maxTokens,
            String  fallbackBehavior,
            boolean includeBody,
            int     maxBodyBytes,
            boolean cacheEnabled,
            int     cacheTtlSeconds
    ) {
        /** Canonical defaults applied when optional fields are absent. */
        public AiModifierConfig {
            if (targetFields    == null || targetFields.isBlank())    targetFields    = "BODY";
            if (fallbackBehavior == null || fallbackBehavior.isBlank()) fallbackBehavior = "PASSTHROUGH";
            if (temperature <= 0)     temperature     = 0.1;
            if (maxTokens   <= 0)     maxTokens       = 1024;
            if (maxBodyBytes <= 0)    maxBodyBytes     = 2048;
            if (cacheTtlSeconds <= 0) cacheTtlSeconds  = 60;
        }
    }

    /**
     * Metadata about the intercepted HTTP request.
     *
     * @param method        HTTP method (GET, POST, …).
     * @param path          Request path (e.g. /api/v1/orders).
     * @param queryString   Raw query string — may be null.
     * @param clientIp      Originating client IP.
     * @param headers       Sanitised request headers — sensitive credentials MUST be
     *                      removed by the gateway before populating this map.
     * @param bodyBase64    Base64-encoded full body up to {@code maxBodyBytes}.
     *                      {@code null} when {@code includeBody=false} or body is absent.
     */
    public record RequestContext(
            @NotBlank String method,
            @NotBlank String path,
            String queryString,
            String clientIp,
            Map<String, String> headers,
            String bodyBase64
    ) {}
}

