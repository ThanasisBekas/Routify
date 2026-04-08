package io.routify.gateway.filter;

import io.routify.common.event.QueryRequest;
import io.routify.common.event.QueryResponse;
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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Gateway filter factory for AI-powered request mutation via routify-ai-service.
 *
 * <h2>Request Flow</h2>
 * <pre>
 * Incoming HTTP request
 *   │
 *   ▼  AiModifierGatewayFilterFactory.apply(Config)
 *   │
 *   ├── Check content-type (skip body for binary types)
 *   │
 *   ├── Buffer request body (up to maxBodyBytes) from the DataBuffer flux
 *   │
 *   ├── Compute SHA-256 body hash for cache keying
 *   │
 *   ├── Build QueryRequest.AiModifierEvaluate from exchange metadata + buffered body
 *   │
 *   ├── Mono.fromCallable( aiServiceClient.modify(request) )
 *   │       .subscribeOn(Schedulers.boundedElastic())   ← NEVER blocks Netty event loop
 *   │       .timeout(Duration.ofMillis(timeoutMs))
 *   │       .onErrorResume(ex → fallbackVerdict)
 *   │
 *   ├── verdict.mutationApplied=false  →  chain.filter(exchange) unchanged (passthrough)
 *   │
 *   └── verdict.mutationApplied=true
 *       ├── Build mutated ServerHttpRequest:
 *       │   ├── Merge mutatedHeaders into request headers
 *       │   └── Wrap body with ServerHttpRequestDecorator (emits mutatedBody bytes)
 *       ├── Inject X-AI-Modifier-Applied: true, X-AI-Modifier-Id: {mutationId}
 *       └── chain.filter(exchange.mutate().request(mutatedRequest).build())
 * </pre>
 *
 * <h2>Content-Type Awareness</h2>
 * Body reading is only performed for textual content types ({@code application/json},
 * {@code text/plain}, {@code application/xml}, {@code text/xml}). Binary content types
 * are automatically skipped — the modifier operates on headers only.
 *
 * <h2>Body Buffering</h2>
 * Spring Cloud Gateway is fully reactive. To read the request body we must:
 * <ol>
 *   <li>Collect the {@code DataBuffer} flux into a byte array.</li>
 *   <li>Pass those bytes to the AI service.</li>
 *   <li>Re-emit the original OR mutated bytes as a {@code ServerHttpRequestDecorator}.</li>
 * </ol>
 * This is the <em>only correct</em> pattern for body mutation in SCG — decorating the
 * request with a new body publisher that emits the bytes exactly once.
 *
 * <h2>Timeout &amp; Fallback</h2>
 * On timeout or circuit-open, {@code fallbackBehavior} determines the outcome:
 * <ul>
 *   <li>{@code PASSTHROUGH} (default) — forward the original request unchanged.</li>
 *   <li>{@code BLOCK} — return 503 Service Unavailable.</li>
 * </ul>
 *
 * <h2>Registered as</h2>
 * {@code AiModifier} — add {@code case "AI_MODIFIER" -> buildAiModifierFilter(snapshot, cfg)} in
 * {@link RouteDefinitionBuilder}.
 */
@Slf4j
@Component
public class AiModifierGatewayFilterFactory
        extends AbstractGatewayFilterFactory<AiModifierGatewayFilterFactory.Config> {

    /** Header injected by the gateway when a mutation was applied. */
    private static final String HEADER_AI_MODIFIER_APPLIED   = "X-AI-Modifier-Applied";
    /** Header carrying the unique mutation ID for distributed tracing. */
    private static final String HEADER_AI_MODIFIER_ID        = "X-AI-Modifier-Id";
    /** Header carrying the mutation type for downstream observability. */
    private static final String HEADER_AI_MODIFIER_TYPE      = "X-AI-Modifier-Type";

    /**
     * Textual content types for which body reading is performed.
     * Binary content types are automatically skipped.
     */
    private static final Set<MediaType> READABLE_CONTENT_TYPES = Set.of(
            MediaType.APPLICATION_JSON,
            MediaType.TEXT_PLAIN,
            MediaType.APPLICATION_XML,
            MediaType.TEXT_XML
    );

    private static final Set<String> REDACTED_HEADER_NAMES = Set.of(
            "authorization", "cookie", "x-api-key", "x-auth-token",
            "proxy-authorization", "x-amz-security-token"
    );

    private final AiServiceClient aiServiceClient;

    public AiModifierGatewayFilterFactory(AiServiceClient aiServiceClient) {
        super(Config.class);
        this.aiServiceClient = aiServiceClient;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();

            // ── Skip body reading for binary content types ────────────────────
            if (!isReadableContentType(request)) {
                // No body to include — evaluate with headers only
                QueryRequest.AiModifierEvaluate rpcRequest =
                        buildRpcRequest(exchange, config, null, null);

                return evaluateAndApply(exchange, chain, config, rpcRequest, new byte[0]);
            }

            // ── Buffer body (respecting maxBodyBytes) ─────────────────────────
            return DataBufferUtils.join(request.getBody())
                    .defaultIfEmpty(new DefaultDataBufferFactory().wrap(new byte[0]))
                    .flatMap(buffer -> {
                        // Read buffered bytes and release the DataBuffer
                        byte[] bodyBytes = new byte[buffer.readableByteCount()];
                        buffer.read(bodyBytes);
                        DataBufferUtils.release(buffer);

                        // Truncate to maxBodyBytes to prevent large RPC payloads
                        byte[] excerptBytes = bodyBytes;
                        if (bodyBytes.length > config.getMaxBodyBytes()) {
                            excerptBytes = new byte[config.getMaxBodyBytes()];
                            System.arraycopy(bodyBytes, 0, excerptBytes, 0, config.getMaxBodyBytes());
                        }

                        String bodyBase64 = config.isIncludeBody() && excerptBytes.length > 0
                                ? Base64.getEncoder().encodeToString(excerptBytes) : null;
                        String bodyHash = config.isIncludeBody() && excerptBytes.length > 0
                                ? AiGatewayFilterFactory.sha256Hex(excerptBytes) : null;

                        // Cache body excerpt as exchange attribute for potential reuse
                        if (bodyBase64 != null) {
                            exchange.getAttributes().put("AI_MODIFIER_BODY_EXCERPT", bodyBase64);
                        }

                        QueryRequest.AiModifierEvaluate rpcRequest =
                                buildRpcRequest(exchange, config, bodyBase64, bodyHash);

                        return evaluateAndApply(exchange, chain, config, rpcRequest, bodyBytes);
                    });
        };
    }

    /**
     * Sends the RPC request to the AI service and applies the mutation verdict.
     */
    private Mono<Void> evaluateAndApply(ServerWebExchange exchange,
                                        org.springframework.cloud.gateway.filter.GatewayFilterChain chain,
                                        Config config,
                                        QueryRequest.AiModifierEvaluate rpcRequest,
                                        byte[] originalBodyBytes) {
        return Mono.fromCallable(() -> aiServiceClient.modify(rpcRequest))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(
                        java.time.Duration.ofMillis(config.getTimeoutMs()),
                        Mono.just(QueryResponse.AiModifierVerdict.passthrough(
                                "AI modifier timeout after %dms — passthrough".formatted(config.getTimeoutMs()),
                                config.getTimeoutMs()))
                )
                .onErrorResume(ex -> {
                    log.warn("AI modifier RPC error: route={} fallback={} error={}",
                            config.getRouteId(), config.getFallbackBehavior(), ex.getMessage());
                    return Mono.just(QueryResponse.AiModifierVerdict.passthrough(
                            "AI modifier error — passthrough: " + ex.getClass().getSimpleName(), 0L));
                })
                .flatMap(verdict -> applyMutation(exchange, chain, verdict,
                        config, originalBodyBytes));
    }

    // ─── Content-type awareness ───────────────────────────────────────────────

    /**
     * Checks whether the request content type is a readable textual type.
     * Returns false for binary types (images, multipart, octet-stream, etc.).
     */
    private boolean isReadableContentType(ServerHttpRequest request) {
        MediaType contentType = request.getHeaders().getContentType();
        if (contentType == null) return false;
        MediaType baseType = new MediaType(contentType.getType(), contentType.getSubtype());
        return READABLE_CONTENT_TYPES.contains(baseType);
    }

    // ─── Mutation application ─────────────────────────────────────────────────

    /**
     * Applies the mutation verdict to the exchange:
     * <ul>
     *   <li>Passthrough → re-emit original body and forward.</li>
     *   <li>Mutation applied → build decorated request with new headers/body.</li>
     *   <li>Fallback BLOCK → return 503.</li>
     * </ul>
     */
    private Mono<Void> applyMutation(ServerWebExchange exchange,
                                     org.springframework.cloud.gateway.filter.GatewayFilterChain chain,
                                     QueryResponse.AiModifierVerdict verdict,
                                     Config config,
                                     byte[] originalBodyBytes) {

        log.info("AI modifier verdict: route={} applied={} type={} cached={} latencyMs={}",
                config.getRouteId(), verdict.mutationApplied(), verdict.mutationType(),
                verdict.cached(), verdict.latencyMs());

        if (!verdict.mutationApplied()) {
            // ── Passthrough: forward with original body ───────────────────────
            if ("BLOCK".equalsIgnoreCase(config.getFallbackBehavior())
                    && "fallback".equals(verdict.mutationId())) {
                return serviceUnavailable(exchange, "AI modifier unavailable");
            }
            ServerHttpRequest rebuilt = rebuildRequest(exchange.getRequest(),
                    Map.of(), originalBodyBytes);
            return chain.filter(exchange.mutate().request(rebuilt).build());
        }

        // ── Mutation applied: replace headers and/or body ─────────────────────
        byte[] mutatedBodyBytes = verdict.mutatedBody() != null
                ? verdict.mutatedBody().getBytes(StandardCharsets.UTF_8)
                : originalBodyBytes;

        Map<String, String> headerChanges = verdict.mutatedHeaders() != null
                ? verdict.mutatedHeaders() : Map.of();

        // Inject observability headers
        java.util.Map<String, String> allHeaderChanges = new java.util.HashMap<>(headerChanges);
        allHeaderChanges.put(HEADER_AI_MODIFIER_APPLIED, "true");
        allHeaderChanges.put(HEADER_AI_MODIFIER_ID,      verdict.mutationId() != null ? verdict.mutationId() : "");
        allHeaderChanges.put(HEADER_AI_MODIFIER_TYPE,    verdict.mutationType() != null ? verdict.mutationType() : "CUSTOM");

        // Update Content-Length to match mutated body
        if (mutatedBodyBytes != originalBodyBytes) {
            allHeaderChanges.put("Content-Length", String.valueOf(mutatedBodyBytes.length));
        }

        ServerHttpRequest mutatedRequest = rebuildRequest(exchange.getRequest(),
                allHeaderChanges, mutatedBodyBytes);

        log.debug("AI modifier mutation applied: route={} headerChanges={} bodyChanged={}",
                config.getRouteId(), headerChanges.size(), verdict.mutatedBody() != null);

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    /**
     * Builds a {@link ServerHttpRequest} that emits the given body bytes exactly once
     * and merges any header changes.
     *
     * <p>Uses {@link ServerHttpRequestDecorator} — the only safe pattern for body re-injection
     * in Spring Cloud Gateway's reactive pipeline.
     */
    private ServerHttpRequest rebuildRequest(ServerHttpRequest original,
                                             Map<String, String> headerChanges,
                                             byte[] bodyBytes) {
        // Build the header mutation
        ServerHttpRequest.Builder builder = original.mutate();
        headerChanges.forEach(builder::header);

        ServerHttpRequest headersOnly = builder.build();

        // Wrap with a body decorator that emits our bytes exactly once
        return new ServerHttpRequestDecorator(headersOnly) {
            @Override
            public Flux<DataBuffer> getBody() {
                if (bodyBytes == null || bodyBytes.length == 0) return Flux.empty();
                DataBuffer db = new DefaultDataBufferFactory().wrap(bodyBytes);
                return Flux.just(db);
            }
        };
    }

    private Mono<Void> serviceUnavailable(ServerWebExchange exchange, String message) {
        return GatewayProblemResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
                .errorCode("AI_MODIFIER_UNAVAILABLE")
                .detail(message)
                .write(exchange);
    }

    // ─── Request building ─────────────────────────────────────────────────────

    private QueryRequest.AiModifierEvaluate buildRpcRequest(ServerWebExchange exchange,
                                                             Config config,
                                                             String bodyBase64,
                                                             String bodyHash) {
        ServerHttpRequest request = exchange.getRequest();

        return new QueryRequest.AiModifierEvaluate(
                config.getRouteId(),
                config.getRouteName(),
                config.getTenantId(),
                config.getModificationPrompt(),
                config.getTargetFields(),
                config.getModelId(),
                config.getTemperature(),
                config.getMaxTokens(),
                config.getFallbackBehavior(),
                config.isIncludeBody(),
                config.getMaxBodyBytes(),
                config.isCacheEnabled(),
                config.getCacheTtlSeconds(),
                config.getTimeoutMs(),
                request.getMethod().name(),
                request.getPath().value(),
                request.getURI().getRawQuery(),
                resolveClientIp(request),
                sanitizeHeaders(request),
                bodyBase64,
                bodyHash,
                request.getHeaders().getFirst(RoutifyHeaders.CORRELATION_ID)
        );
    }

    private String resolveClientIp(ServerHttpRequest request) {
        String xff = request.getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return Optional.ofNullable(request.getRemoteAddress())
                .map(InetSocketAddress::getHostString)
                .orElse("unknown");
    }

    private Map<String, String> sanitizeHeaders(ServerHttpRequest request) {
        return request.getHeaders().toSingleValueMap().entrySet().stream()
                .limit(20)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> REDACTED_HEADER_NAMES.contains(e.getKey().toLowerCase())
                                ? "[REDACTED]" : e.getValue()
                ));
    }

    // ─── Config ──────────────────────────────────────────────────────────────

    /**
     * AI modifier configuration — populated from {@code FilterDefinition.config} JSONB.
     */
    @Data
    public static class Config {

        /**
         * Natural-language mutation instruction.
         * Required — the filter logs a warning and passesthrough if absent.
         * Example: "Scrub all email addresses from the JSON body"
         */
        private String  modificationPrompt = "";

        /** Comma-separated targets: BODY, HEADERS, or BODY,HEADERS. Default: BODY. */
        private String  targetFields       = "BODY";

        /** Optional LLM model override. Null/blank = service default. */
        private String  modelId            = "";

        /** LLM temperature (0.0–1.0). Default 0.1. */
        private double  temperature        = 0.1;

        /** Maximum LLM response tokens. Default 1024. */
        private int     maxTokens          = 1024;

        /**
         * Behaviour when the AI service is unavailable:
         * <ul>
         *   <li>{@code PASSTHROUGH} (default) — forward the original request unchanged.</li>
         *   <li>{@code BLOCK} — reject with 503.</li>
         * </ul>
         */
        private String  fallbackBehavior   = "PASSTHROUGH";

        /** Whether to include the request body in the mutation request. */
        private boolean includeBody        = true;

        /** Maximum bytes of body to include. Default 2048. */
        private int     maxBodyBytes       = 2048;

        /** Whether to use Redis mutation caching. Default false. */
        private boolean cacheEnabled       = false;

        /** Cache TTL in seconds. Default 60. */
        private int     cacheTtlSeconds    = 60;

        /** Hard RPC timeout in milliseconds. Default 4000. */
        private int     timeoutMs          = 4000;

        // ── Injected by RouteDefinitionBuilder ──
        private String routeId   = "";
        private String routeName = "";
        private String tenantId  = "";
    }
}

