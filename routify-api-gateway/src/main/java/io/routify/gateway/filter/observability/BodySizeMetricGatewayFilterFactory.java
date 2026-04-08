package io.routify.gateway.filter.observability;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lightweight, zero-copy filter that records request and response body sizes as
 * Micrometer {@link DistributionSummary} metrics without reading or buffering
 * body content.
 *
 * <p>For requests and responses with a {@code Content-Length} header the value
 * is recorded directly (fast path). For chunked transfers without
 * {@code Content-Length}, the body {@link Flux}<{@link DataBuffer}> is wrapped
 * with a non-buffering counter that sums {@code readableByteCount()} per chunk,
 * recording the total after the stream completes.
 *
 * <p>Metrics:
 * <ul>
 *   <li>{@code routify.request.body.size} — distribution summary tagged by
 *       {@code routeId}, {@code method}, and any custom tags</li>
 *   <li>{@code routify.response.body.size} — distribution summary tagged by
 *       {@code routeId}, {@code method}, {@code status}, and any custom tags</li>
 * </ul>
 *
 * <p>Config params:
 * <ul>
 *   <li>{@code includeRequest} — record request body size (default: true)</li>
 *   <li>{@code includeResponse} — record response body size (default: true)</li>
 *   <li>{@code tags} — additional static Micrometer tags (key-value map)</li>
 * </ul>
 *
 * <p>Filter type: {@code BODY_SIZE_METRIC}
 */
@Slf4j
@Component
public class BodySizeMetricGatewayFilterFactory
        extends AbstractGatewayFilterFactory<BodySizeMetricGatewayFilterFactory.Config> {

    private final MeterRegistry meterRegistry;

    /** Lazily-created summaries keyed by composite key (metric name + tag signature). */
    private final ConcurrentHashMap<String, DistributionSummary> summaryCache = new ConcurrentHashMap<>();

    public BodySizeMetricGatewayFilterFactory(MeterRegistry meterRegistry) {
        super(Config.class);
        this.meterRegistry = meterRegistry;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return new BodySizeMetricFilter(config);
    }

    // ─── Inner filter ─────────────────────────────────────────────────────────

    private class BodySizeMetricFilter implements GatewayFilter, Ordered {

        private final Config config;

        BodySizeMetricFilter(Config config) {
            this.config = config;
        }

        @Override
        public int getOrder() {
            // Run early so it wraps the full downstream chain
            return Ordered.HIGHEST_PRECEDENCE + 100;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            String routeId = extractRouteId(exchange);
            String method = exchange.getRequest().getMethod() != null
                    ? exchange.getRequest().getMethod().name() : "UNKNOWN";

            ServerWebExchange mutatedExchange = exchange;

            // ─── Request body size ────────────────────────────────────────────
            if (config.isIncludeRequest()) {
                long contentLength = resolveContentLength(exchange.getRequest().getHeaders());
                if (contentLength >= 0) {
                    // Fast path: Content-Length present
                    recordRequestSize(routeId, method, contentLength, config);
                } else {
                    // Chunked: wrap request body to count bytes
                    LongAdder requestByteCounter = new LongAdder();
                    ServerHttpRequest decoratedRequest = new ServerHttpRequestDecorator(exchange.getRequest()) {
                        @Override
                        public Flux<DataBuffer> getBody() {
                            return super.getBody().doOnNext(buffer ->
                                    requestByteCounter.add(buffer.readableByteCount()));
                        }
                    };
                    mutatedExchange = exchange.mutate().request(decoratedRequest).build();

                    // Record the total after the chain completes
                    ServerWebExchange finalExchange = mutatedExchange;
                    return chain.filter(wrapResponse(finalExchange, routeId, method, config))
                            .doFinally(signal -> {
                                long total = requestByteCounter.sum();
                                if (total > 0) {
                                    recordRequestSize(routeId, method, total, config);
                                }
                            });
                }
            }

            // ─── Response body size (when request was Content-Length path) ────
            if (config.isIncludeResponse()) {
                mutatedExchange = wrapResponse(mutatedExchange, routeId, method, config);
            }

            return chain.filter(mutatedExchange);
        }
    }

    // ─── Response decorator ───────────────────────────────────────────────────

    /**
     * Wraps the response to count body bytes.
     * If Content-Length is present on the response, records immediately.
     * Otherwise wraps the body Flux to count bytes per chunk.
     */
    private ServerWebExchange wrapResponse(ServerWebExchange exchange,
                                            String routeId, String method,
                                            Config config) {
        ServerHttpResponse originalResponse = exchange.getResponse();
        ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                String status = getStatusCode() != null
                        ? String.valueOf(getStatusCode().value()) : "0";

                // Fast path: Content-Length present on response
                long contentLength = resolveContentLength(getHeaders());
                if (contentLength >= 0) {
                    recordResponseSize(routeId, method, status, contentLength, config);
                    return super.writeWith(body);
                }

                // Chunked: count bytes without buffering
                if (body instanceof Flux<? extends DataBuffer> flux) {
                    LongAdder byteCounter = new LongAdder();
                    Flux<? extends DataBuffer> countingFlux = flux
                            .doOnNext(buffer -> byteCounter.add(buffer.readableByteCount()))
                            .doOnComplete(() -> recordResponseSize(
                                    routeId, method, status, byteCounter.sum(), config));
                    return super.writeWith(countingFlux);
                }

                // Mono body — single buffer
                LongAdder byteCounter = new LongAdder();
                Flux<? extends DataBuffer> fluxBody = Flux.from(body)
                        .doOnNext(buffer -> byteCounter.add(buffer.readableByteCount()))
                        .doOnComplete(() -> recordResponseSize(
                                routeId, method, status, byteCounter.sum(), config));
                return super.writeWith(fluxBody);
            }

            @Override
            public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
                // For streaming responses, delegate to writeWith via Flux.flatMap
                return writeWith(Flux.from(body).flatMap(Flux::from));
            }
        };

        return exchange.mutate().response(decoratedResponse).build();
    }

    // ─── Metric recording ─────────────────────────────────────────────────────

    private void recordRequestSize(String routeId, String method, long bytes, Config config) {
        List<Tag> tags = buildTags(routeId, method, null, config);
        getOrCreateSummary("routify.request.body.size", "Request body size in bytes", tags)
                .record(bytes);
        log.debug("BodySizeMetric: request size={} bytes (route={} method={})", bytes, routeId, method);
    }

    private void recordResponseSize(String routeId, String method, String status,
                                     long bytes, Config config) {
        List<Tag> tags = buildTags(routeId, method, status, config);
        getOrCreateSummary("routify.response.body.size", "Response body size in bytes", tags)
                .record(bytes);
        log.debug("BodySizeMetric: response size={} bytes (route={} method={} status={})",
                bytes, routeId, method, status);
    }

    private DistributionSummary getOrCreateSummary(String name, String description, List<Tag> tags) {
        // Build a cache key from name + sorted tags
        String cacheKey = name + ":" + tags.stream()
                .map(t -> t.getKey() + "=" + t.getValue())
                .sorted()
                .reduce("", (a, b) -> a + "|" + b);

        return summaryCache.computeIfAbsent(cacheKey, k ->
                DistributionSummary.builder(name)
                        .description(description)
                        .tags(tags)
                        .register(meterRegistry));
    }

    // ─── Tag building ─────────────────────────────────────────────────────────

    private static List<Tag> buildTags(String routeId, String method, String status, Config config) {
        List<Tag> tags = new ArrayList<>();
        tags.add(Tag.of("routeId", routeId));
        tags.add(Tag.of("method", method));
        if (status != null) {
            tags.add(Tag.of("status", status));
        }
        // Add custom tags from config
        Map<String, String> customTags = config.getTags();
        if (customTags != null) {
            customTags.forEach((k, v) -> tags.add(Tag.of(k, v != null ? v : "")));
        }
        return tags;
    }

    // ─── Route ID extraction ──────────────────────────────────────────────────

    private static String extractRouteId(ServerWebExchange exchange) {
        var routeAttr = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (routeAttr instanceof Route route) {
            String id = route.getId();
            if (id != null && id.contains("::")) {
                return id.substring(id.indexOf("::") + 2);
            }
            return id != null ? id : "unknown";
        }
        return "unknown";
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Resolves Content-Length from headers. Returns -1 if absent or invalid.
     */
    static long resolveContentLength(HttpHeaders headers) {
        long cl = headers.getContentLength();
        return cl >= 0 ? cl : -1;
    }

    // ─── Config ───────────────────────────────────────────────────────────────

    @Data
    public static class Config {
        /** Record request body size. Default: true. */
        private boolean includeRequest = true;
        /** Record response body size. Default: true. */
        private boolean includeResponse = true;
        /** Additional static Micrometer tags (key-value map). */
        private Map<String, String> tags;
    }
}

