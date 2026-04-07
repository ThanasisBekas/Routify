package io.routify.gateway.filter.reliability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.routify.gateway.filter.shared.GatewayProblemResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;

/**
 * Idempotency Key filter — deduplicates write requests using a client-provided
 * idempotency key (per the emerging IETF standard).
 *
 * <h3>Flow:</h3>
 * <ol>
 *   <li><b>First request:</b> Acquire a Redis NX lock with status {@code PROCESSING},
 *       forward to upstream, capture response via {@link ServerHttpResponseDecorator},
 *       store cached response (status + headers + Base64 body) in Redis Hash, update
 *       status to {@code COMPLETE}. Returns {@code Idempotency-Key-Status: MISS}.</li>
 *   <li><b>Replay:</b> Key exists with status {@code COMPLETE} → return cached response
 *       directly without forwarding. Returns {@code Idempotency-Key-Status: HIT}.</li>
 *   <li><b>Concurrent duplicate:</b> Key exists with status {@code PROCESSING} → return
 *       {@code 409 Conflict}.</li>
 * </ol>
 *
 * <h3>Redis key format:</h3>
 * {@code routify:idempotency:{routeId}:{idempotencyKey}}
 *
 * <p>Filter type: {@code IDEMPOTENCY_KEY}
 *
 * @see io.routify.common.domain.FilterType#IDEMPOTENCY_KEY
 */
@Slf4j
@Component
public class IdempotencyKeyGatewayFilterFactory
        extends AbstractGatewayFilterFactory<IdempotencyKeyGatewayFilterFactory.Config> {

    private static final String REDIS_PREFIX = "routify:idempotency:";
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_COMPLETE = "COMPLETE";

    private static final String FIELD_STATUS = "status";
    private static final String FIELD_HTTP_STATUS = "httpStatus";
    private static final String FIELD_HEADERS = "headers";
    private static final String FIELD_BODY = "body";

    private static final String IDEMPOTENCY_KEY_STATUS_HEADER = "Idempotency-Key-Status";

    private final ReactiveStringRedisTemplate redis;
    private final MeterRegistry meterRegistry;

    public IdempotencyKeyGatewayFilterFactory(ReactiveStringRedisTemplate redis,
                                              MeterRegistry meterRegistry) {
        super(Config.class);
        this.redis = redis;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public GatewayFilter apply(Config config) {
        String headerName = config.getHeaderName() != null && !config.getHeaderName().isBlank()
                ? config.getHeaderName() : "Idempotency-Key";
        int ttlSeconds = config.getTtlSeconds() > 0 ? config.getTtlSeconds() : 86400;
        Set<HttpMethod> enforcedMethods = parseMethods(config.getMethods());
        boolean requireHeader = config.isRequireHeader();
        int maxCachedBodySize = config.getMaxCachedBodySize() > 0 ? config.getMaxCachedBodySize() : 65536;

        Counter hitCounter = Counter.builder("routify.filter.idempotency.hit")
                .description("Idempotency key cache hits (replay)")
                .register(meterRegistry);
        Counter missCounter = Counter.builder("routify.filter.idempotency.miss")
                .description("Idempotency key cache misses (first execution)")
                .register(meterRegistry);
        Counter conflictCounter = Counter.builder("routify.filter.idempotency.conflict")
                .description("Idempotency key concurrent duplicates (409)")
                .register(meterRegistry);

        log.info("IdempotencyKey filter configured: header={} ttl={}s methods={} requireHeader={} maxBody={}",
                headerName, ttlSeconds, enforcedMethods, requireHeader, maxCachedBodySize);

        return new IdempotencyKeyFilter(
                headerName, ttlSeconds, enforcedMethods, requireHeader, maxCachedBodySize,
                redis, hitCounter, missCounter, conflictCounter);
    }

    // ─── Inner filter ──────────────────────────────────────────────────────────

    static final class IdempotencyKeyFilter implements GatewayFilter, Ordered {

        /** Runs after auth filters but early enough to short-circuit on replay. */
        private static final int FILTER_ORDER = -100;

        private final String headerName;
        private final int ttlSeconds;
        private final Set<HttpMethod> enforcedMethods;
        private final boolean requireHeader;
        private final int maxCachedBodySize;
        private final ReactiveStringRedisTemplate redis;
        private final Counter hitCounter;
        private final Counter missCounter;
        private final Counter conflictCounter;

        IdempotencyKeyFilter(String headerName, int ttlSeconds,
                             Set<HttpMethod> enforcedMethods, boolean requireHeader,
                             int maxCachedBodySize,
                             ReactiveStringRedisTemplate redis,
                             Counter hitCounter, Counter missCounter, Counter conflictCounter) {
            this.headerName = headerName;
            this.ttlSeconds = ttlSeconds;
            this.enforcedMethods = enforcedMethods;
            this.requireHeader = requireHeader;
            this.maxCachedBodySize = maxCachedBodySize;
            this.redis = redis;
            this.hitCounter = hitCounter;
            this.missCounter = missCounter;
            this.conflictCounter = conflictCounter;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            HttpMethod method = exchange.getRequest().getMethod();

            // Only enforce on configured methods
            if (method == null || !enforcedMethods.contains(method)) {
                return chain.filter(exchange);
            }

            // Extract idempotency key from header
            String idempotencyKey = exchange.getRequest().getHeaders().getFirst(headerName);

            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                if (requireHeader) {
                    // Reject — header required but missing
                    return GatewayProblemResponse.status(HttpStatus.BAD_REQUEST)
                            .errorCode("IDEMPOTENCY_KEY_REQUIRED")
                            .detail("The %s header is required for %s requests on this route.",
                                    headerName, method.name())
                            .write(exchange);
                }
                // Pass through without idempotency logic
                return chain.filter(exchange);
            }

            String routeId = extractRouteId(exchange);
            if (routeId == null) {
                return chain.filter(exchange);
            }

            String redisKey = REDIS_PREFIX + routeId + ":" + idempotencyKey;

            // Check Redis for existing key
            return redis.opsForHash().entries(redisKey)
                    .collectMap(e -> e.getKey().toString(), e -> e.getValue().toString())
                    .flatMap(cached -> {
                        if (!cached.isEmpty()) {
                            String status = cached.getOrDefault(FIELD_STATUS, "");
                            if (STATUS_COMPLETE.equals(status)) {
                                // Replay — return cached response
                                hitCounter.increment();
                                return handleReplay(exchange, cached);
                            }
                            if (STATUS_PROCESSING.equals(status)) {
                                // Concurrent duplicate
                                conflictCounter.increment();
                                return GatewayProblemResponse.status(HttpStatus.CONFLICT)
                                        .errorCode("IDEMPOTENCY_KEY_IN_PROGRESS")
                                        .detail("A request with idempotency key '%s' is currently being processed. " +
                                                "Please retry after it completes.", idempotencyKey)
                                        .write(exchange);
                            }
                        }

                        // First request — acquire lock
                        return handleFirstRequest(exchange, chain, redisKey, idempotencyKey);
                    });
        }

        @Override
        public int getOrder() {
            return FILTER_ORDER;
        }

        // ── First request ───────────────────────────────────────────────────────

        private Mono<Void> handleFirstRequest(ServerWebExchange exchange,
                                               GatewayFilterChain chain,
                                               String redisKey,
                                               String idempotencyKey) {
            // Set NX lock: PROCESSING
            Map<String, String> lockEntry = Map.of(FIELD_STATUS, STATUS_PROCESSING);

            return redis.opsForValue()
                    .setIfAbsent(redisKey + ":lock", STATUS_PROCESSING, Duration.ofSeconds(ttlSeconds))
                    .flatMap(acquired -> {
                        if (Boolean.FALSE.equals(acquired)) {
                            // Another request beat us — treat as concurrent duplicate
                            conflictCounter.increment();
                            return GatewayProblemResponse.status(HttpStatus.CONFLICT)
                                    .errorCode("IDEMPOTENCY_KEY_IN_PROGRESS")
                                    .detail("A request with idempotency key '%s' is currently being processed. " +
                                            "Please retry after it completes.", idempotencyKey)
                                    .write(exchange);
                        }

                        missCounter.increment();

                        // Store PROCESSING hash entry
                        return redis.opsForHash().putAll(redisKey, lockEntry)
                                .then(redis.expire(redisKey, Duration.ofSeconds(ttlSeconds)))
                                .then(forwardAndCapture(exchange, chain, redisKey));
                    });
        }

        private Mono<Void> forwardAndCapture(ServerWebExchange exchange,
                                              GatewayFilterChain chain,
                                              String redisKey) {
            ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(exchange.getResponse()) {
                @Override
                public Mono<Void> writeWith(org.reactivestreams.Publisher<? extends DataBuffer> body) {
                    // Inject MISS header
                    getDelegate().getHeaders().set(IDEMPOTENCY_KEY_STATUS_HEADER, "MISS");

                    int statusCode = getDelegate().getStatusCode() != null
                            ? getDelegate().getStatusCode().value() : 200;

                    return DataBufferUtils.join(Flux.from(body))
                            .flatMap(dataBuffer -> {
                                byte[] bytes = new byte[dataBuffer.readableByteCount()];
                                dataBuffer.read(bytes);
                                DataBufferUtils.release(dataBuffer);

                                // Cache the response in Redis (if body not too large)
                                Mono<Void> storeMono;
                                if (bytes.length <= maxCachedBodySize) {
                                    Map<String, String> cacheEntry = new LinkedHashMap<>();
                                    cacheEntry.put(FIELD_STATUS, STATUS_COMPLETE);
                                    cacheEntry.put(FIELD_HTTP_STATUS, String.valueOf(statusCode));
                                    cacheEntry.put(FIELD_HEADERS, serializeHeaders(getDelegate().getHeaders()));
                                    cacheEntry.put(FIELD_BODY, Base64.getEncoder().encodeToString(bytes));

                                    storeMono = redis.opsForHash().putAll(redisKey, cacheEntry)
                                            .then(redis.expire(redisKey, Duration.ofSeconds(ttlSeconds)))
                                            .then(redis.delete(redisKey + ":lock"))
                                            .then();
                                } else {
                                    log.debug("IdempotencyKey: response body ({} bytes) exceeds maxCachedBodySize ({}) — skipping cache for key {}",
                                            bytes.length, maxCachedBodySize, redisKey);
                                    // Still mark as complete to avoid perpetual PROCESSING state,
                                    // but without body — subsequent replays will re-execute.
                                    // Actually for oversized body: clean up lock, don't cache.
                                    storeMono = redis.delete(redisKey)
                                            .then(redis.delete(redisKey + ":lock"))
                                            .then();
                                }

                                DataBuffer outBuffer = getDelegate().bufferFactory().wrap(bytes);
                                return storeMono.then(super.writeWith(Mono.just(outBuffer)));
                            });
                }
            };

            return chain.filter(exchange.mutate().response(decoratedResponse).build());
        }

        // ── Replay ──────────────────────────────────────────────────────────────

        private Mono<Void> handleReplay(ServerWebExchange exchange,
                                         Map<String, String> cached) {
            log.debug("IdempotencyKey: replaying cached response");

            ServerHttpResponse response = exchange.getResponse();

            // Restore status
            int status = Integer.parseInt(cached.getOrDefault(FIELD_HTTP_STATUS, "200"));
            response.setStatusCode(HttpStatus.valueOf(status));

            // Restore headers
            String headersStr = cached.get(FIELD_HEADERS);
            if (headersStr != null) {
                deserializeHeaders(headersStr, response.getHeaders());
            }

            // Inject HIT header
            response.getHeaders().set(IDEMPOTENCY_KEY_STATUS_HEADER, "HIT");

            // Restore body
            String bodyBase64 = cached.get(FIELD_BODY);
            if (bodyBase64 != null && !bodyBase64.isEmpty()) {
                byte[] bodyBytes = Base64.getDecoder().decode(bodyBase64);
                response.getHeaders().setContentLength(bodyBytes.length);
                DataBuffer buffer = response.bufferFactory().wrap(bodyBytes);
                return response.writeWith(Mono.just(buffer));
            }

            return response.setComplete();
        }

        // ── Utilities ───────────────────────────────────────────────────────────

        private static String extractRouteId(ServerWebExchange exchange) {
            var route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            if (route instanceof org.springframework.cloud.gateway.route.Route r) {
                String id = r.getId();
                if (id != null && id.contains("::")) {
                    return id.substring(id.indexOf("::") + 2);
                }
                return id;
            }
            return null;
        }

        private static String serializeHeaders(org.springframework.http.HttpHeaders headers) {
            StringBuilder sb = new StringBuilder();
            headers.forEach((name, values) -> {
                String lower = name.toLowerCase(Locale.ROOT);
                // Skip hop-by-hop, transfer-encoding, and internal headers
                if (lower.equals("transfer-encoding") || lower.equals("connection")
                        || lower.equals(IDEMPOTENCY_KEY_STATUS_HEADER.toLowerCase(Locale.ROOT))) {
                    return;
                }
                sb.append(name).append(":");
                sb.append(String.join(",", values));
                sb.append("\n");
            });
            return sb.toString();
        }

        private static void deserializeHeaders(String serialized,
                                                org.springframework.http.HttpHeaders target) {
            if (serialized == null || serialized.isEmpty()) return;
            String[] lines = serialized.split("\n");
            for (String line : lines) {
                int colonIdx = line.indexOf(':');
                if (colonIdx > 0 && colonIdx < line.length() - 1) {
                    String name = line.substring(0, colonIdx);
                    String value = line.substring(colonIdx + 1);
                    // Skip content-length — will be set based on actual body size
                    if (!"content-length".equalsIgnoreCase(name)) {
                        target.set(name, value);
                    }
                }
            }
        }
    }

    // ─── Utility methods ────────────────────────────────────────────────────────

    private static Set<HttpMethod> parseMethods(String methods) {
        if (methods == null || methods.isBlank()) {
            return Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH);
        }
        Set<HttpMethod> result = new HashSet<>();
        for (String m : methods.split(",")) {
            try {
                result.add(HttpMethod.valueOf(m.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                log.warn("IdempotencyKey: unrecognised HTTP method '{}' — skipping", m.trim());
            }
        }
        return result.isEmpty() ? Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH) : result;
    }

    @Data
    public static class Config {
        /** Header name carrying the idempotency key. Default: Idempotency-Key. */
        private String headerName = "Idempotency-Key";
        /** How long to remember processed keys in seconds. Default: 86400 (24 hours). */
        private int ttlSeconds = 86400;
        /** Comma-separated HTTP methods to enforce idempotency on. Default: POST,PUT,PATCH. */
        private String methods = "POST,PUT,PATCH";
        /** When true, requests without the idempotency header are rejected with 400. */
        private boolean requireHeader = false;
        /** Max response body bytes to cache. Default: 65536 (64 KB). */
        private int maxCachedBodySize = 65536;
    }
}

