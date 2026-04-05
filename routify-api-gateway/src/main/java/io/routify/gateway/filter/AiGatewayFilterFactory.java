package io.routify.gateway.filter;

import io.routify.common.event.QueryRequest;
import io.routify.common.event.QueryResponse;
import io.routify.common.event.RabbitTopology;
import io.routify.common.web.RoutifyHeaders;
import io.routify.gateway.client.AiServiceClient;
import io.routify.gateway.routing.RouteDefinitionBuilder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Gateway filter factory for AI-powered request filtering via routify-ai-service.
 *
 * <h2>Request Flow</h2>
 * <pre>
 * Incoming HTTP request
 *   │
 *   ▼  AiGatewayFilterFactory.apply(Config)
 *   │
 *   ├── [ASYNC mode] → chain.filter() immediately (non-blocking)
 *   │   └── fire-and-forget RabbitMQ call (for audit only)
 *   │
 *   └── [SYNC mode]
 *       │
 *       ├── Build QueryRequest.AiFilterEvaluate from exchange metadata
 *       │
 *       ├── Mono.fromCallable( aiServiceClient.evaluate(request) )
 *       │       .subscribeOn(Schedulers.boundedElastic())   ← NEVER blocks Netty event loop
 *       │       .timeout(Duration.ofMillis(timeoutMs))
 *       │       .onErrorResume(ex → fallbackVerdict)
 *       │
 *       ├── verdict.action == BLOCK  →  write 403 Forbidden + Problem JSON
 *       ├── verdict.action == FLAG   →  chain.filter() + inject X-AI-Filter-Flag: true
 *       └── verdict.action == ALLOW  →  chain.filter()
 * </pre>
 *
 * <h2>Why {@code Schedulers.boundedElastic()}?</h2>
 * Spring Cloud Gateway runs on Project Reactor / Netty. The Netty I/O event loop threads
 * must never block. {@code RabbitTemplate.sendAndReceive()} is a blocking call (it parks
 * the calling thread until the reply arrives or the timeout fires). Wrapping it in
 * {@code Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())} moves the
 * blocking work onto a dedicated thread pool designed exactly for this purpose.
 *
 * <h2>Timeout & Fallback</h2>
 * The dedicated AI service {@link RabbitTemplate} uses {@link RabbitTopology#AI_FILTER_REPLY_TIMEOUT_MS}
 * (3.5 s). An additional Reactor {@code .timeout()} operator at the filter level provides
 * a second safety net. On any failure path the configured {@code fallbackAction}
 * (default: {@code ALLOW}) is applied immediately without blocking the gateway.
 *
 * <h2>Registered as</h2>
 * {@code AiFilter} — add {@code case "AI_FILTER" -> customFilter("AiFilter", cfg)} in
 * {@link RouteDefinitionBuilder}.
 */
@Slf4j
@Component
public class AiGatewayFilterFactory
        extends AbstractGatewayFilterFactory<AiGatewayFilterFactory.Config> {

    /** Header added to requests when the AI filter verdict is FLAG. */
    private static final String HEADER_AI_FLAG    = "X-AI-Filter-Flag";
    /** Header carrying the AI verdict action (for downstream observability). */
    private static final String HEADER_AI_ACTION  = "X-AI-Filter-Action";
    /** Header carrying the evaluation ID for distributed tracing. */
    private static final String HEADER_AI_EVAL_ID = "X-AI-Filter-Eval-Id";

    /**
     * Sensitive headers that must never be forwarded to the AI service prompt.
     * Defence-in-depth: these should already be stripped by the gateway auth filters,
     * but we redact them here regardless.
     */
    private static final Set<String> REDACTED_HEADER_NAMES = Set.of(
            "authorization", "cookie", "x-api-key", "x-auth-token",
            "proxy-authorization", "x-amz-security-token"
    );

    private final AiServiceClient aiServiceClient;

    public AiGatewayFilterFactory(AiServiceClient aiServiceClient) {
        super(Config.class);
        this.aiServiceClient = aiServiceClient;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();

            // ── ASYNC mode: allow immediately, evaluate off the critical path ──
            if ("ASYNC".equalsIgnoreCase(config.getEvaluationMode())) {
                QueryRequest.AiFilterEvaluate rpcRequest = buildRpcRequest(exchange, config);
                // Fire-and-forget on bounded elastic scheduler — never blocks gateway
                Mono.fromCallable(() -> aiServiceClient.evaluate(rpcRequest))
                        .subscribeOn(Schedulers.boundedElastic())
                        .subscribe(
                                v -> log.debug("AI filter ASYNC verdict: route={} action={}", config.getRouteId(), v.action()),
                                e -> log.warn("AI filter ASYNC evaluation failed: route={} error={}", config.getRouteId(), e.getMessage())
                        );
                return chain.filter(exchange);
            }

            // ── SYNC mode: block request until verdict arrives ─────────────────
            QueryRequest.AiFilterEvaluate rpcRequest = buildRpcRequest(exchange, config);

            return Mono.fromCallable(() -> aiServiceClient.evaluate(rpcRequest))
                    // Move blocking RabbitMQ call off Netty event loop
                    .subscribeOn(Schedulers.boundedElastic())
                    // Hard timeout as a Reactor safety net (RabbitTemplate has its own timeout too)
                    .timeout(
                            java.time.Duration.ofMillis(config.getTimeoutMs()),
                            Mono.just(QueryResponse.AiFilterVerdict.fallback(
                                    config.getFallbackAction(),
                                    "AI filter timeout after %dms — fallback applied".formatted(config.getTimeoutMs()),
                                    config.getTimeoutMs()))
                    )
                    // Any exception (RPC error, circuit open, serialization failure) → fallback
                    .onErrorResume(ex -> {
                        log.warn("AI filter RPC error: route={} fallback={} error={}",
                                config.getRouteId(), config.getFallbackAction(), ex.getMessage());
                        return Mono.just(QueryResponse.AiFilterVerdict.fallback(
                                config.getFallbackAction(),
                                "AI filter error — fallback applied: " + ex.getClass().getSimpleName(),
                                0L));
                    })
                    .flatMap(verdict -> enforceVerdict(exchange, chain, verdict, config));
        };
    }

    // ─── Verdict enforcement ─────────────────────────────────────────────────

    /**
     * Enforces the AI filter verdict on the exchange.
     *
     * <ul>
     *   <li>{@code BLOCK}  → 403 Forbidden with Problem JSON body</li>
     *   <li>{@code FLAG}   → {@code chain.filter()} with {@code X-AI-Filter-Flag: true} header</li>
     *   <li>{@code ALLOW}  → {@code chain.filter()} unchanged</li>
     * </ul>
     *
     * <p>The confidence threshold check is delegated to the AI service, which applies
     * it before returning the verdict. The gateway simply enforces whatever verdict arrives.
     */
    private Mono<Void> enforceVerdict(ServerWebExchange exchange,
                                      org.springframework.cloud.gateway.filter.GatewayFilterChain chain,
                                      QueryResponse.AiFilterVerdict verdict,
                                      Config config) {
        log.info("AI filter verdict: route={} action={} confidence={} cached={} latencyMs={}",
                config.getRouteId(), verdict.action(), verdict.confidence(),
                verdict.cached(), verdict.latencyMs());

        return switch (verdict.action().toUpperCase()) {
            case "BLOCK" -> {
                log.debug("AI filter BLOCK: route={} reason='{}'", config.getRouteId(), verdict.reason());
                yield blocked(exchange, verdict);
            }
            case "FLAG" -> {
                log.debug("AI filter FLAG: route={} reason='{}'", config.getRouteId(), verdict.reason());
                // Allow the request but inject flag + metadata headers for downstream inspection
                ServerHttpRequest mutated = exchange.getRequest().mutate()
                        .header(HEADER_AI_FLAG,    "true")
                        .header(HEADER_AI_ACTION,  "FLAG")
                        .header(HEADER_AI_EVAL_ID, verdict.evaluationId() != null ? verdict.evaluationId() : "")
                        .build();
                yield chain.filter(exchange.mutate().request(mutated).build());
            }
            default -> {
                // ALLOW or any unrecognised action — let the request proceed
                log.debug("AI filter ALLOW: route={}", config.getRouteId());
                yield chain.filter(exchange);
            }
        };
    }

    /**
     * Writes an HTTP 403 Forbidden response with RFC 9457 Problem JSON body.
     * Mirrors the {@code tooManyRequests()} pattern in {@link FixedWindowRateLimitGatewayFilterFactory}.
     */
    private Mono<Void> blocked(ServerWebExchange exchange, QueryResponse.AiFilterVerdict verdict) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().set(HttpHeaders.CONTENT_TYPE, "application/problem+json");

        // Sanitize the reason to prevent JSON injection
        String safeReason = verdict.reason() != null
                ? verdict.reason().replace("\"", "'").replace("\n", " ")
                : "Request blocked by AI filter policy";

        String body = """
                {"type":"about:blank","title":"Forbidden","status":403,\
                "errorCode":"AI_FILTER_BLOCKED",\
                "detail":"%s",\
                "evaluationId":"%s"}""".formatted(
                safeReason,
                verdict.evaluationId() != null ? verdict.evaluationId() : "");

        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    // ─── Request building ─────────────────────────────────────────────────────

    /**
     * Builds the {@link QueryRequest.AiFilterEvaluate} RPC request from the current exchange state.
     *
     * <p>Sensitive headers are redacted before inclusion. The body excerpt is only
     * included when {@code config.isIncludeBody() == true} and a cached body attribute
     * is present on the exchange (set by a body-caching filter earlier in the chain).
     */
    private QueryRequest.AiFilterEvaluate buildRpcRequest(ServerWebExchange exchange, Config config) {
        ServerHttpRequest request = exchange.getRequest();

        return new QueryRequest.AiFilterEvaluate(
                // Route identity
                config.getRouteId(),
                config.getRouteName(),
                config.getTenantId(),
                // AI filter config
                config.getPolicyDescription(),
                config.getEvaluationMode(),
                config.isIncludeBody(),
                config.getMaxBodyBytes(),
                config.getFallbackAction(),
                config.getConfidenceThreshold(),
                config.isCacheEnabled(),
                config.getCacheTtlSeconds(),
                // Request context
                request.getMethod().name(),
                request.getPath().value(),
                request.getURI().getRawQuery(),
                resolveClientIp(request),
                sanitizeHeaders(request),
                resolveBodyExcerpt(exchange, config),
                // Auth context (injected by JWT filter earlier in chain)
                request.getHeaders().getFirst(RoutifyHeaders.AUTH_USER_ID),
                request.getHeaders().getFirst(RoutifyHeaders.AUTH_ROLE),
                // Correlation ID for distributed tracing
                request.getHeaders().getFirst(RoutifyHeaders.CORRELATION_ID)
        );
    }

    /**
     * Resolves the client IP from X-Forwarded-For (first entry) or falls back to
     * the remote socket address. Prevents IP spoofing by only reading the first value.
     */
    private String resolveClientIp(ServerHttpRequest request) {
        String xff = request.getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return Optional.ofNullable(request.getRemoteAddress())
                .map(InetSocketAddress::getHostString)
                .orElse("unknown");
    }

    /**
     * Returns a sanitized copy of the request headers with all sensitive values redacted.
     * Limits header count to 20 to prevent excessively large RPC payloads.
     */
    private Map<String, String> sanitizeHeaders(ServerHttpRequest request) {
        return request.getHeaders().toSingleValueMap().entrySet().stream()
                .limit(20)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> REDACTED_HEADER_NAMES.contains(e.getKey().toLowerCase())
                                ? "[REDACTED]"
                                : e.getValue()
                ));
    }

    /**
     * Returns the cached body excerpt if body inclusion is enabled.
     *
     * <p>The body excerpt must be cached by a body-caching filter (e.g. a
     * {@code ModifyRequestBodyGatewayFilterFactory} or custom caching filter)
     * earlier in the chain and stored as an exchange attribute under
     * {@code "AI_FILTER_BODY_EXCERPT"}.  If no cached body is found, returns null.
     */
    private String resolveBodyExcerpt(ServerWebExchange exchange, Config config) {
        if (!config.isIncludeBody()) return null;
        Object cached = exchange.getAttribute("AI_FILTER_BODY_EXCERPT");
        return cached instanceof String s ? s : null;
    }

    // ─── Config ──────────────────────────────────────────────────────────────

    /**
     * AI filter configuration — populated from the {@code FilterDefinition.config} JSONB
     * via Spring Cloud Gateway's property binder.
     *
     * <p>All fields have safe defaults so a minimal config (just {@code policyDescription})
     * is sufficient for basic usage.
     */
    @Data
    public static class Config {

        /**
         * Natural-language policy rule the LLM must enforce.
         * Required — the filter logs a warning and defaults to ALLOW if absent.
         * Example: "Block requests containing SQL injection patterns"
         */
        private String policyDescription = "";

        /** SYNC (default) blocks the request; ASYNC evaluates off the critical path. */
        private String  evaluationMode      = "SYNC";

        /** Whether to include a body excerpt in the evaluation prompt. */
        private boolean includeBody         = false;

        /** Maximum bytes of request body to include (prevents large RPC payloads). */
        private int     maxBodyBytes        = 512;

        /**
         * Verdict to apply when the AI service is unavailable, circuit open, or times out.
         * ALLOW (fail-open) is the safe default for initial rollout.
         * Set to BLOCK (fail-closed) for strict security postures.
         */
        private String  fallbackAction      = "ALLOW";

        /**
         * Minimum LLM confidence to honour the verdict (0.0–1.0).
         * Below this threshold the {@code fallbackAction} is applied instead.
         */
        private double  confidenceThreshold = 0.85;

        /** Whether the AI service should consult the Redis verdict cache. */
        private boolean cacheEnabled        = true;

        /** Cache TTL in seconds. */
        private int     cacheTtlSeconds     = 30;

        /**
         * Hard timeout for the RabbitMQ RPC call in milliseconds.
         * Acts as a second safety net beyond the RabbitTemplate's own reply timeout.
         */
        private int     timeoutMs           = 3000;

        // ── Fields set programmatically by RouteDefinitionBuilder from route metadata ──
        // These are NOT expected in the filter's JSONB config — they are injected from
        // the route snapshot's routeId / tenantId / name fields.

        /** Route UUID — injected from route snapshot metadata. */
        private String routeId   = "";
        /** Route name — injected from route snapshot metadata. */
        private String routeName = "";
        /** Tenant UUID — injected from route snapshot metadata. */
        private String tenantId  = "";
    }
}

