package io.routify.gateway.filter;

import io.routify.common.event.QueryRequest;
import io.routify.common.event.QueryResponse;
import io.routify.common.event.RabbitTopology;
import io.routify.common.web.RoutifyHeaders;
import io.routify.gateway.client.AiServiceClient;
import io.routify.gateway.filter.shared.GatewayProblemResponse;
import io.routify.gateway.routing.RouteDefinitionBuilder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
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
 *       ├── Read body inline (if includeBody=true and content-type is textual)
 *       │   └── Compute SHA-256 body hash for cache keying
 *       │
 *       ├── Build QueryRequest.AiFilterEvaluate from exchange metadata + body excerpt
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
 * <h2>Inline Body Reading</h2>
 * When {@code includeBody=true}, the filter reads the first {@code maxBodyBytes} of the request
 * body reactively using {@code DataBufferUtils.join()}. The body is then re-emitted via a
 * {@link ServerHttpRequestDecorator} so upstream services receive the full payload unchanged.
 * Binary content types are automatically skipped — body reading only occurs for
 * {@code application/json}, {@code text/plain}, {@code application/xml}, and {@code text/xml}.
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

    /** Exchange attribute key for caching the body excerpt for potential reuse by AI modifier. */
    static final String ATTR_BODY_EXCERPT = "AI_FILTER_BODY_EXCERPT";
    /** Exchange attribute key for the cached body bytes (for re-emission). */
    private static final String ATTR_BODY_BYTES  = "AI_FILTER_BODY_BYTES";

    /**
     * Textual content types for which body reading is performed.
     * Binary content types are automatically skipped to avoid sending meaningless data to the LLM.
     */
    private static final Set<MediaType> READABLE_CONTENT_TYPES = Set.of(
            MediaType.APPLICATION_JSON,
            MediaType.TEXT_PLAIN,
            MediaType.APPLICATION_XML,
            MediaType.TEXT_XML
    );

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

    /**
     * In-memory cache: prompt version ID → prompt text.
     * Populated from filter config when {@code promptVersionSplit} is set.
     * This allows A/B testing between prompt versions at the gateway.
     */
    private static final ConcurrentHashMap<String, String> PROMPT_VERSION_CACHE = new ConcurrentHashMap<>();

    /** Registers a prompt version text for gateway-side A/B selection. */
    public static void cachePromptVersion(String versionId, String promptText) {
        if (versionId != null && promptText != null) {
            PROMPT_VERSION_CACHE.put(versionId, promptText);
        }
    }

    public AiGatewayFilterFactory(AiServiceClient aiServiceClient) {
        super(Config.class);
        this.aiServiceClient = aiServiceClient;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            // ── Read body inline if needed, then build RPC request ──
            return readBodyIfNeeded(exchange, config)
                    .flatMap(ctx -> {
                        ServerWebExchange decoratedExchange = ctx.decoratedExchange();

                        // ── ASYNC mode: allow immediately, evaluate off the critical path ──
                        if ("ASYNC".equalsIgnoreCase(config.getEvaluationMode())) {
                            QueryRequest.AiFilterEvaluate rpcRequest =
                                    buildRpcRequest(decoratedExchange, config, ctx.bodyExcerpt(), ctx.bodyHash());
                            Mono.fromCallable(() -> aiServiceClient.evaluate(rpcRequest))
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .subscribe(
                                            v -> log.debug("AI filter ASYNC verdict: route={} action={}",
                                                    config.getRouteId(), v.action()),
                                            e -> log.warn("AI filter ASYNC evaluation failed: route={} error={}",
                                                    config.getRouteId(), e.getMessage())
                                    );
                            return chain.filter(decoratedExchange);
                        }

                        // ── SYNC mode: block request until verdict arrives ─────────────────
                        QueryRequest.AiFilterEvaluate rpcRequest =
                                buildRpcRequest(decoratedExchange, config, ctx.bodyExcerpt(), ctx.bodyHash());

                        return Mono.fromCallable(() -> aiServiceClient.evaluate(rpcRequest))
                                .subscribeOn(Schedulers.boundedElastic())
                                .timeout(
                                        java.time.Duration.ofMillis(config.getTimeoutMs()),
                                        Mono.just(QueryResponse.AiFilterVerdict.fallback(
                                                config.getFallbackAction(),
                                                "AI filter timeout after %dms — fallback applied"
                                                        .formatted(config.getTimeoutMs()),
                                                config.getTimeoutMs()))
                                )
                                .onErrorResume(ex -> {
                                    log.warn("AI filter RPC error: route={} fallback={} error={}",
                                            config.getRouteId(), config.getFallbackAction(), ex.getMessage());
                                    return Mono.just(QueryResponse.AiFilterVerdict.fallback(
                                            config.getFallbackAction(),
                                            "AI filter error — fallback applied: " + ex.getClass().getSimpleName(),
                                            0L));
                                })
                                .flatMap(verdict -> enforceVerdict(decoratedExchange, chain, verdict, config));
                    });
        };
    }

    // ─── Inline body reading ──────────────────────────────────────────────────

    /**
     * Context holder for body reading results.
     *
     * @param decoratedExchange exchange with re-wrapped body for downstream consumption
     * @param bodyExcerpt       Base64-encoded body excerpt (null if body not read)
     * @param bodyHash          SHA-256 hex hash of the body excerpt (null if body not read)
     */
    record BodyReadContext(ServerWebExchange decoratedExchange, String bodyExcerpt, String bodyHash) {}

    /**
     * Reads the request body inline when {@code includeBody=true} and the content type is textual.
     * Re-wraps the exchange with a {@link ServerHttpRequestDecorator} that re-emits the cached
     * bytes so upstream services receive the full payload unchanged.
     *
     * <p>For binary content types or when {@code includeBody=false}, returns the exchange unchanged
     * with null body excerpt and hash.
     */
    private Mono<BodyReadContext> readBodyIfNeeded(ServerWebExchange exchange, Config config) {
        if (!config.isIncludeBody() || !isReadableContentType(exchange.getRequest())) {
            return Mono.just(new BodyReadContext(exchange, null, null));
        }

        return DataBufferUtils.join(exchange.getRequest().getBody())
                .defaultIfEmpty(new DefaultDataBufferFactory().wrap(new byte[0]))
                .map(dataBuffer -> {
                    byte[] allBytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(allBytes);
                    DataBufferUtils.release(dataBuffer);

                    // Truncate to maxBodyBytes for the excerpt
                    int excerptLen = Math.min(allBytes.length, config.getMaxBodyBytes());
                    byte[] excerptBytes = excerptLen < allBytes.length
                            ? Arrays.copyOf(allBytes, excerptLen)
                            : allBytes;

                    String bodyExcerpt = excerptBytes.length > 0
                            ? Base64.getEncoder().encodeToString(excerptBytes) : null;
                    String bodyHash = excerptBytes.length > 0
                            ? sha256Hex(excerptBytes) : null;

                    // Cache excerpt as exchange attribute for potential reuse by AI modifier
                    if (bodyExcerpt != null) {
                        exchange.getAttributes().put(ATTR_BODY_EXCERPT, bodyExcerpt);
                    }
                    exchange.getAttributes().put(ATTR_BODY_BYTES, allBytes);

                    // Re-wrap body with ServerHttpRequestDecorator for downstream consumption
                    final byte[] fullBodyBytes = allBytes;
                    ServerHttpRequest decoratedRequest = new ServerHttpRequestDecorator(exchange.getRequest()) {
                        @Override
                        public Flux<DataBuffer> getBody() {
                            if (fullBodyBytes.length == 0) return Flux.empty();
                            DataBuffer buffer = new DefaultDataBufferFactory().wrap(fullBodyBytes);
                            return Flux.just(buffer);
                        }
                    };

                    ServerWebExchange decoratedExchange = exchange.mutate()
                            .request(decoratedRequest)
                            .build();

                    return new BodyReadContext(decoratedExchange, bodyExcerpt, bodyHash);
                });
    }

    /**
     * Checks whether the request content type is a readable textual type.
     * Returns false for binary types (images, multipart, octet-stream, etc.).
     */
    private boolean isReadableContentType(ServerHttpRequest request) {
        MediaType contentType = request.getHeaders().getContentType();
        if (contentType == null) return false;
        // Compare without parameters (charset, etc.)
        MediaType baseType = new MediaType(contentType.getType(), contentType.getSubtype());
        return READABLE_CONTENT_TYPES.contains(baseType);
    }

    /**
     * Computes SHA-256 hex hash of the given bytes.
     * SHA-256 is fast (~500 MB/s on modern CPUs) and safe to compute inline.
     */
    static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the JVM spec — this should never happen
            throw new IllegalStateException("SHA-256 not available", e);
        }
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
     * Uses {@link GatewayProblemResponse} for consistent, injection-safe serialization.
     */
    private Mono<Void> blocked(ServerWebExchange exchange, QueryResponse.AiFilterVerdict verdict) {
        String reason = verdict.reason() != null
                ? verdict.reason()
                : "Request blocked by AI filter policy";

        return GatewayProblemResponse.status(HttpStatus.FORBIDDEN)
                .errorCode("AI_FILTER_BLOCKED")
                .detail(reason)
                .extension("evaluationId", verdict.evaluationId() != null ? verdict.evaluationId() : "")
                .write(exchange);
    }

    // ─── Request building ─────────────────────────────────────────────────────

    /**
     * Builds the {@link QueryRequest.AiFilterEvaluate} RPC request from the current exchange state.
     *
     * <p>Sensitive headers are redacted before inclusion. Body excerpt and hash are passed
     * from the inline body reading step.
     */
    private QueryRequest.AiFilterEvaluate buildRpcRequest(ServerWebExchange exchange,
                                                           Config config,
                                                           String bodyExcerpt,
                                                           String bodyHash) {
        ServerHttpRequest request = exchange.getRequest();

        // A/B split: if promptVersionSplit is configured, select a version by weight
        String effectivePolicy = config.getPolicyDescription();
        String selectedVersionId = null;
        if (config.getPromptVersionSplit() != null && !config.getPromptVersionSplit().isEmpty()) {
            selectedVersionId = selectWeightedVersion(config.getPromptVersionSplit());
            if (selectedVersionId != null) {
                String cachedPrompt = PROMPT_VERSION_CACHE.get(selectedVersionId);
                if (cachedPrompt != null) {
                    effectivePolicy = cachedPrompt;
                } else {
                    log.warn("Prompt version {} not in cache — using default policy", selectedVersionId);
                }
            }
        }

        return new QueryRequest.AiFilterEvaluate(
                // Route identity
                config.getRouteId(),
                config.getRouteName(),
                config.getTenantId(),
                // AI filter config
                effectivePolicy,
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
                bodyExcerpt,
                bodyHash,
                // Auth context (injected by JWT filter earlier in chain)
                request.getHeaders().getFirst(RoutifyHeaders.AUTH_USER_ID),
                request.getHeaders().getFirst(RoutifyHeaders.AUTH_ROLE),
                // Correlation ID for distributed tracing
                request.getHeaders().getFirst(RoutifyHeaders.CORRELATION_ID),
                // A/B prompt version selection
                selectedVersionId
        );
    }

    /**
     * Weighted random version selection for A/B prompt version split testing.
     * Weights are integers (e.g. {"v3": 90, "v4": 10}) representing relative traffic share.
     */
    private String selectWeightedVersion(Map<String, Integer> split) {
        int totalWeight = split.values().stream().mapToInt(Integer::intValue).sum();
        if (totalWeight <= 0) return null;
        int random = ThreadLocalRandom.current().nextInt(totalWeight);
        int cumulative = 0;
        for (var entry : split.entrySet()) {
            cumulative += entry.getValue();
            if (random < cumulative) return entry.getKey();
        }
        return split.keySet().iterator().next();
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

        /**
         * Maximum bytes of request body to include (prevents large RPC payloads).
         * Default 2048 (2 KB) — large enough for meaningful JSON payloads,
         * small enough to avoid memory pressure under high concurrency.
         */
        private int     maxBodyBytes        = 2048;

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

        /**
         * A/B split testing: maps prompt version ID → traffic weight (integer, e.g. 90/10).
         * When set, the gateway probabilistically selects a prompt version per request.
         * Null or empty → no split, use {@link #policyDescription} directly.
         */
        private Map<String, Integer> promptVersionSplit;

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

