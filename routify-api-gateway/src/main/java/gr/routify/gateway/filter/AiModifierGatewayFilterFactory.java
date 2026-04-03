package gr.routify.gateway.filter;

import gr.routify.common.event.QueryRequest;
import gr.routify.common.event.QueryResponse;
import gr.routify.common.event.RabbitTopology;
import gr.routify.common.web.RoutifyHeaders;
import gr.routify.gateway.client.AiServiceClient;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
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
 *   ├── Buffer request body (up to maxBodyBytes) from the DataBuffer flux
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
 * {@link gr.routify.gateway.routing.RouteDefinitionBuilder}.
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

            // ── Buffer body (respecting maxBodyBytes) ─────────────────────────
            return request.getBody()
                    .collect(exchange.getResponse().bufferFactory()::allocateBuffer, DataBuffer::write)
                    .defaultIfEmpty(exchange.getResponse().bufferFactory().allocateBuffer(0))
                    .flatMap(buffer -> {
                        // Read buffered bytes and release the DataBuffer
                        byte[] bodyBytes = new byte[buffer.readableByteCount()];
                        buffer.read(bodyBytes);
                        org.springframework.core.io.buffer.DataBufferUtils.release(buffer);

                        // Truncate to maxBodyBytes to prevent large RPC payloads
                        if (bodyBytes.length > config.getMaxBodyBytes()) {
                            byte[] truncated = new byte[config.getMaxBodyBytes()];
                            System.arraycopy(bodyBytes, 0, truncated, 0, config.getMaxBodyBytes());
                            bodyBytes = truncated;
                        }

                        final byte[] finalBodyBytes = bodyBytes;
                        String bodyBase64 = config.isIncludeBody() && finalBodyBytes.length > 0
                                ? Base64.getEncoder().encodeToString(finalBodyBytes) : null;

                        QueryRequest.AiModifierEvaluate rpcRequest =
                                buildRpcRequest(exchange, config, bodyBase64);

                        // Re-capture for use in closure
                        final byte[] originalBodyBytes = finalBodyBytes;

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
                    });
        };
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
                DataBuffer buffer = getDelegate().getHeaders()
                        // bufferFactory() is not directly on the request — use exchange
                        // We create a buffer from the response factory (same pool in Netty)
                        // This is the standard pattern used by ModifyRequestBodyGatewayFilterFactory
                        .getFirst("X-Request-Body-Hint") != null
                        ? null : null; // placeholder — actual buffer creation below
                // Create buffer via the default Netty allocator
                org.springframework.core.io.buffer.DataBufferFactory factory =
                        new org.springframework.core.io.buffer.DefaultDataBufferFactory();
                DataBuffer db = factory.wrap(bodyBytes);
                return Flux.just(db);
            }
        };
    }

    private Mono<Void> serviceUnavailable(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        response.getHeaders().set(HttpHeaders.CONTENT_TYPE, "application/problem+json");
        String body = """
                {"type":"about:blank","title":"Service Unavailable","status":503,\
                "errorCode":"AI_MODIFIER_UNAVAILABLE","detail":"%s"}""".formatted(message);
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    // ─── Request building ─────────────────────────────────────────────────────

    private QueryRequest.AiModifierEvaluate buildRpcRequest(ServerWebExchange exchange,
                                                             Config config,
                                                             String bodyBase64) {
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

