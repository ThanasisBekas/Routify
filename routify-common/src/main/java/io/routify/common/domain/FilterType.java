package io.routify.common.domain;

/**
 * Filter types available in the Routify filter chain.
 * Each type corresponds to a concrete GatewayFilterFactory implementation in routify-api-gateway.
 *
 * <p>Keep this enum in sync with:
 * <ul>
 *   <li>The {@code FilterType} TypeScript union in routify-dashboard/src/types/index.ts</li>
 *   <li>Every {@code *GatewayFilterFactory} class in routify-api-gateway</li>
 * </ul>
 */
public enum FilterType {

    // ─── Authentication ───────────────────────────────────────────────────────

    /** Validate API Key from header or query param — ApiKeyAuthGatewayFilterFactory */
    AUTH_API_KEY,
    /** Validate HTTP Basic credentials — BasicAuthGatewayFilterFactory */
    AUTH_BASIC,
    /** Validate JWT (RS256/HS256) — JwtAuthGatewayFilterFactory */
    AUTH_JWT,
    /** Mutual TLS — validate client certificate against the registry — MtlsAuthGatewayFilterFactory */
    AUTH_MTLS,
    /** OAuth2 token introspection — OAuth2TokenIntrospectGatewayFilterFactory */
    AUTH_OAUTH2,
    /** Client-ID header authentication — ClientIdAuthGatewayFilterFactory */
    AUTH_CLIENT_ID,
    /**
     * Certificate Vault authentication — authenticate caller by verifying their PEM certificate
     * (from a request header) against the Cert Vault registry.
     * Injects rich X.509 identity headers downstream.
     * — CertVaultAuthGatewayFilterFactory
     */
    AUTH_CERT_VAULT,

    // ─── Downstream Auth Injection ────────────────────────────────────────────

    /** Inject Basic Auth credentials into outbound downstream requests — DownstreamBasicAuthGatewayFilterFactory */
    DOWNSTREAM_BASIC_AUTH,
    /** Acquire an OAuth2 client-credentials token and inject as Bearer downstream — DownstreamOAuth2BearerGatewayFilterFactory */
    DOWNSTREAM_BEARER_CC,
    /**
     * RFC 8693 Token Exchange — exchanges the incoming bearer token for a downstream-specific
     * token via a configured OAuth2 token endpoint. Supports Caffeine token caching,
     * configurable fallback (REJECT / PASS_THROUGH / STRIP), and fully non-blocking WebClient calls.
     * — OAuth2TokenRelayGatewayFilterFactory
     */
    OAUTH2_TOKEN_RELAY,

    // ─── Rate Limiting ────────────────────────────────────────────────────────

    /** Fixed-window rate limiter backed by Redis — FixedWindowRateLimitGatewayFilterFactory */
    RATE_LIMIT_FIXED_WINDOW,
    /** Sliding-window rate limiter backed by Redis sorted sets — SlidingWindowRateLimitGatewayFilterFactory */
    RATE_LIMIT_SLIDING_WINDOW,

    // ─── Request / Response Modification ────────────────────────────────────

    /** Add, set or remove request headers — RequestHeaderModifyGatewayFilterFactory */
    REQUEST_HEADER_MODIFY,
    /** Add, set or remove response headers — ResponseHeaderModifyGatewayFilterFactory */
    RESPONSE_HEADER_MODIFY,
    /**
     * Regex-based response header value rewriting — rewrites header values using
     * pre-compiled Java regex patterns with capture group references ({@code $1}, {@code $2}).
     * Primary use cases: rewriting {@code Location} redirect headers from internal to external URLs,
     * rewriting {@code Set-Cookie} domain attributes.
     * Includes catastrophic backtracking protection.
     * — ResponseHeaderRewriteGatewayFilterFactory
     */
    RESPONSE_HEADER_REWRITE,

    // ─── Body Transformation ─────────────────────────────────────────────────

    /** Jolt JSON-to-JSON transformation on request body — JoltTransformGatewayFilterFactory */
    BODY_JOLT_TRANSFORM,

    // ─── Validation ──────────────────────────────────────────────────────────

    /** Validate request body against a JSON Schema — JsonSchemaValidateGatewayFilterFactory */
    VALIDATE_JSON_SCHEMA,
    /** Enforce per-route maximum request body size (413 on exceed) — RequestSizeLimitGatewayFilterFactory */
    REQUEST_SIZE_LIMIT,
    /**
     * Parse incoming GraphQL queries and reject those exceeding configurable depth,
     * complexity, or alias limits. Optionally blocks introspection queries and batched
     * queries beyond a maximum batch size. Uses graphql-java AST parser (no execution engine).
     * — GraphQLDepthLimitGatewayFilterFactory
     */
    GRAPHQL_DEPTH_LIMIT,

    // ─── Performance ──────────────────────────────────────────────────────────

    /** Per-route Redis-backed response cache with configurable TTL — ResponseCacheGatewayFilterFactory */
    RESPONSE_CACHE,
    /**
     * Transparently decompresses {@code gzip}, {@code br} (Brotli), and {@code zstd}
     * encoded request bodies before forwarding to upstream. Includes zip bomb protection
     * via {@code maxDecompressedSize} limit, header cleanup ({@code Content-Encoding}
     * removal, {@code Content-Length} update), and {@code X-Original-Encoding} header
     * injection for downstream observability.
     * — RequestDecompressGatewayFilterFactory
     */
    REQUEST_DECOMPRESS,

    // ─── Reliability ─────────────────────────────────────────────────────────

    /**
     * Idempotency Key filter — deduplicates write requests using a client-provided
     * idempotency key (per the emerging IETF standard). First request executes and
     * caches the response in Redis. Replay returns the cached response without
     * forwarding to upstream. Concurrent duplicates are rejected with 409 Conflict.
     * — IdempotencyKeyGatewayFilterFactory
     */
    IDEMPOTENCY_KEY,

    // ─── Resilience ──────────────────────────────────────────────────────────

    /** Per-route request timeout (504 on exceed) — RequestTimeoutGatewayFilterFactory */
    TIMEOUT,
    /**
     * Per-route Resilience4j circuit breaker with configurable failure/slow-call thresholds,
     * half-open probing, state broadcast via WebSocket, and manual override via admin-api.
     * Replaces the deprecated {@link #CIRCUIT_BREAKER} filter.
     * — CircuitBreakerV2GatewayFilterFactory
     */
    CIRCUIT_BREAKER_V2,
    /**
     * Per-route custom retry filter with exponential backoff, jitter, idempotency-aware
     * retry logic, and configurable retry conditions. Replaces the deprecated {@link #RETRY} filter.
     * — RetryV2GatewayFilterFactory
     */
    RETRY_V2,

    // ─── Routing ─────────────────────────────────────────────────────────────

    /** Conditionally rewrite upstream URI based on a header or query param — ConditionalRouteGatewayFilterFactory */
    CONDITIONAL_ROUTE,
    /** Route to an alternative upstream when userId in request body is in an allowlist — UserIdPayloadRoutingGatewayFilterFactory */
    USER_ID_PAYLOAD_ROUTING,
    /** Route to geographically closest upstream using MaxMind GeoIP2 lookups — GeoRouteGatewayFilterFactory */
    GEO_ROUTE,

    // ─── Security ──────────────────────────────────────────────────────────────

    /** IP allowlist/denylist — block or allow requests by client IP or CIDR range — IpAccessControlGatewayFilterFactory */
    IP_ACCESS_CONTROL,

    // ─── Certificate / TLS ───────────────────────────────────────────────────

    /**
     * Enforce certificate rotation — reject requests that present a revoked or expired
     * client certificate — CertRotationGatewayFilterFactory
     */
    CERT_ROTATION,
    /**
     * Cert Vault expiry check — block or warn when a vault certificate is expired,
     * revoked, or approaching its expiry window — CertVaultExpiryCheckGatewayFilterFactory
     */
    CERT_VAULT_EXPIRY_CHECK,

    // ─── Versioning ──────────────────────────────────────────────────────────

    /** Inject API version via header, query param, or path prefix — ApiVersioningGatewayFilterFactory */
    API_VERSIONING,

    // ─── Observability ───────────────────────────────────────────────────────

    /** Inject or propagate X-Correlation-Id (UUID generated if absent) — CorrelationIdGatewayFilterFactory */
    CORRELATION_ID,
    /** Log request/response metadata and publish telemetry to Kafka — RequestLoggerGatewayFilterFactory */
    REQUEST_LOGGER,
    /** Resolve tenant context and control X-Tenant-Id propagation to upstream — TenantContextGatewayFilterFactory */
    TENANT_CONTEXT,
    /** Inject OWASP security response headers driven by gateway config — SecurityHeadersGatewayFilterFactory */
    SECURITY_HEADERS,
    /** Increment a custom Micrometer counter with optional dynamic tags — CustomMetricGatewayFilterFactory */
    CUSTOM_METRIC,
    /**
     * Lightweight, zero-copy filter that records request and response body sizes as
     * Micrometer distribution summaries without reading or buffering body content.
     * — BodySizeMetricGatewayFilterFactory
     */
    BODY_SIZE_METRIC,

    // ─── Custom ──────────────────────────────────────────────────────────────

    /** Evaluate a SpEL expression — returning false rejects with 403 — SpelCustomGatewayFilterFactory */
    CUSTOM_SPEL,

    // ─── AI ──────────────────────────────────────────────────────────────────

    /**
     * LLM-powered dynamic filter — evaluates requests against a natural-language policy
     * defined by the operator.  Delegates to routify-ai-service (Spring AI / ChatClient).
     * — AiGatewayFilterFactory
     *
     * <p>Keep in sync with the {@code 'AI_FILTER'} literal in:
     * <ul>
     *   <li>routify-dashboard/src/types/index.ts (FilterType union)</li>
     *   <li>AiGatewayFilterFactory in routify-api-gateway</li>
     * </ul>
     */
    AI_FILTER,

    /**
     * LLM-powered request/response mutation filter — uses a local LLM to dynamically
     * transform the incoming request (e.g. PII scrubbing, payload translation, header
     * rewriting) and re-injects the mutated request back into the gateway pipeline.
     * Delegates to routify-ai-service — AiModifierGatewayFilterFactory
     *
     * <p>Keep in sync with the {@code 'AI_MODIFIER'} literal in:
     * <ul>
     *   <li>routify-dashboard/src/types/index.ts (FilterType union)</li>
     *   <li>AiModifierGatewayFilterFactory in routify-api-gateway</li>
     * </ul>
     */
    AI_MODIFIER,

    // ─── Legacy (no gateway factory — kept for backward compatibility with existing DB records) ──

    /** @deprecated No gateway factory implementation. Kept for DB compatibility only. */
    @Deprecated AUTH_NONE,
    /** @deprecated No gateway factory implementation. Kept for DB compatibility only. */
    @Deprecated RATE_LIMIT_TOKEN_BUCKET,
    /** @deprecated No gateway factory implementation. Kept for DB compatibility only. */
    @Deprecated PATH_REWRITE,
    /** @deprecated No gateway factory implementation. Kept for DB compatibility only. */
    @Deprecated PATH_STRIP_PREFIX,
    /** @deprecated No gateway factory implementation. Kept for DB compatibility only. */
    @Deprecated PATH_ADD_PREFIX,
    /** @deprecated No gateway factory implementation. Kept for DB compatibility only. */
    @Deprecated QUERY_PARAM_MODIFY,
    /** @deprecated No gateway factory implementation. Kept for DB compatibility only. */
    @Deprecated BODY_JSONATA_TRANSFORM,
    /** @deprecated No gateway factory implementation. Kept for DB compatibility only. */
    @Deprecated BODY_SPEL_TRANSFORM,
    /** @deprecated No gateway factory implementation. Kept for DB compatibility only. */
    @Deprecated VALIDATE_REGEX,
    /** @deprecated No gateway factory implementation. Kept for DB compatibility only. */
    @Deprecated VALIDATE_SIZE,
    /** @deprecated No gateway factory implementation. Kept for DB compatibility only. */
    @Deprecated CIRCUIT_BREAKER,
    /** @deprecated No gateway factory implementation. Kept for DB compatibility only. */
    @Deprecated RETRY
}
