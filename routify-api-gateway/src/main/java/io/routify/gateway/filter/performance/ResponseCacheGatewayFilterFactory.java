package io.routify.gateway.filter.performance;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-route, Redis-backed response caching with configurable TTL,
 * HTTP {@code Cache-Control} header semantics, and multiple cache key strategies.
 *
 * <h3>Cache flow:</h3>
 * <ol>
 *   <li><b>HIT</b>: Read from Redis, return directly without forwarding upstream.</li>
 *   <li><b>MISS</b>: Forward to upstream, capture response body via
 *       {@link ServerHttpResponseDecorator}, store in Redis, return to client.</li>
 * </ol>
 *
 * <h3>Cache key strategies:</h3>
 * <ul>
 *   <li>{@code PATH_QUERY} — {@code routeId + path + sorted query params}</li>
 *   <li>{@code PATH_QUERY_HEADERS} — above + specified {@code varyHeaders} values</li>
 * </ul>
 *
 * <h3>Redis key format:</h3>
 * {@code routify:cache:{routeId}:{sha256(derivedKey)}}
 *
 * <p>Filter type: {@code RESPONSE_CACHE}
 */
@Slf4j
@Component
public class ResponseCacheGatewayFilterFactory
        extends AbstractGatewayFilterFactory<ResponseCacheGatewayFilterFactory.Config> {

    private static final String REDIS_PREFIX = "routify:cache:";
    private static final String FIELD_STATUS = "status";
    private static final String FIELD_HEADERS = "headers";
    private static final String FIELD_BODY = "body";
    private static final String FIELD_CACHED_AT = "cachedAt";

    private final ReactiveStringRedisTemplate redis;
    private final MeterRegistry meterRegistry;

    /**
     * Tracks all active route IDs that have response-cache filters applied.
     * Used by the cache purge command consumer to iterate and delete keys.
     */
    private final Set<String> activeRouteIds = ConcurrentHashMap.newKeySet();

    public ResponseCacheGatewayFilterFactory(ReactiveStringRedisTemplate redis,
                                             MeterRegistry meterRegistry) {
        super(Config.class);
        this.redis = redis;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public GatewayFilter apply(Config config) {
        int ttlSeconds = config.getTtlSeconds();
        int maxCachedBodySize = config.getMaxCachedBodySize();
        Set<String> cacheableMethods = parseMethods(config.getMethods());
        Set<Integer> cacheableStatuses = parseStatusCodes(config.getStatusCodes());
        String keyStrategy = config.getKeyStrategy() != null ? config.getKeyStrategy() : "PATH_QUERY";
        String[] varyHeaders = config.getVaryHeaders() != null ? config.getVaryHeaders() : new String[0];
        boolean respectCacheControl = config.isRespectCacheControl();
        boolean addCacheHeaders = config.isAddCacheHeaders();

        Counter hitCounter = Counter.builder("routify.filter.cache.hit")
                .description("Response cache hits")
                .register(meterRegistry);
        Counter missCounter = Counter.builder("routify.filter.cache.miss")
                .description("Response cache misses")
                .register(meterRegistry);
        Counter skipCounter = Counter.builder("routify.filter.cache.skip")
                .description("Response cache skips (body too large, no-store, etc.)")
                .register(meterRegistry);

        log.info("ResponseCache filter configured: ttl={}s maxBody={} methods={} statuses={} keyStrategy={} respectCC={} addHeaders={}",
                ttlSeconds, maxCachedBodySize, cacheableMethods, cacheableStatuses, keyStrategy, respectCacheControl, addCacheHeaders);

        return new ResponseCacheFilter(
                ttlSeconds, maxCachedBodySize, cacheableMethods, cacheableStatuses,
                keyStrategy, varyHeaders, respectCacheControl, addCacheHeaders,
                redis, hitCounter, missCounter, skipCounter, activeRouteIds);
    }

    /**
     * Purge all cached responses for a specific route by scanning Redis keys.
     *
     * @param routeId the route ID to purge
     * @return a Mono that completes when purge is done
     */
    public Mono<Long> purgeRoute(UUID routeId) {
        String pattern = REDIS_PREFIX + routeId + ":*";
        log.info("Purging cache for route {}: pattern={}", routeId, pattern);

        return redis.keys(pattern)
                .collectList()
                .flatMap(keys -> {
                    if (keys.isEmpty()) {
                        log.debug("No cache keys found for route {}", routeId);
                        return Mono.just(0L);
                    }
                    log.info("Deleting {} cache keys for route {}", keys.size(), routeId);
                    return redis.delete(Flux.fromIterable(keys));
                });
    }

    // ─── Inner filter ──────────────────────────────────────────────────────────

    static final class ResponseCacheFilter implements GatewayFilter, Ordered {

        /** Runs early to short-circuit on cache HIT, but after auth filters. */
        private static final int FILTER_ORDER = -200;

        private final int ttlSeconds;
        private final int maxCachedBodySize;
        private final Set<String> cacheableMethods;
        private final Set<Integer> cacheableStatuses;
        private final String keyStrategy;
        private final String[] varyHeaders;
        private final boolean respectCacheControl;
        private final boolean addCacheHeaders;
        private final ReactiveStringRedisTemplate redis;
        private final Counter hitCounter;
        private final Counter missCounter;
        private final Counter skipCounter;
        private final Set<String> activeRouteIds;

        ResponseCacheFilter(int ttlSeconds, int maxCachedBodySize,
                            Set<String> cacheableMethods, Set<Integer> cacheableStatuses,
                            String keyStrategy, String[] varyHeaders,
                            boolean respectCacheControl, boolean addCacheHeaders,
                            ReactiveStringRedisTemplate redis,
                            Counter hitCounter, Counter missCounter, Counter skipCounter,
                            Set<String> activeRouteIds) {
            this.ttlSeconds = ttlSeconds;
            this.maxCachedBodySize = maxCachedBodySize;
            this.cacheableMethods = cacheableMethods;
            this.cacheableStatuses = cacheableStatuses;
            this.keyStrategy = keyStrategy;
            this.varyHeaders = varyHeaders;
            this.respectCacheControl = respectCacheControl;
            this.addCacheHeaders = addCacheHeaders;
            this.redis = redis;
            this.hitCounter = hitCounter;
            this.missCounter = missCounter;
            this.skipCounter = skipCounter;
            this.activeRouteIds = activeRouteIds;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            // Only cache configured HTTP methods
            HttpMethod method = exchange.getRequest().getMethod();
            if (method == null || !cacheableMethods.contains(method.name())) {
                return chain.filter(exchange);
            }

            String routeId = extractRouteId(exchange);
            if (routeId == null) {
                return chain.filter(exchange);
            }
            activeRouteIds.add(routeId);

            String cacheKey = buildCacheKey(routeId, exchange);

            return redis.opsForHash().entries(cacheKey)
                    .collectMap(e -> e.getKey().toString(), e -> e.getValue().toString())
                    .flatMap(cached -> {
                        if (cached.isEmpty()) {
                            // MISS — forward to upstream and capture response
                            return handleCacheMiss(exchange, chain, cacheKey, routeId);
                        }
                        // HIT — return cached response directly
                        return handleCacheHit(exchange, cached, cacheKey);
                    });
        }

        @Override
        public int getOrder() {
            return FILTER_ORDER;
        }

        // ── Cache HIT ──────────────────────────────────────────────────────────

        private Mono<Void> handleCacheHit(ServerWebExchange exchange,
                                           Map<String, String> cached,
                                           String cacheKey) {
            hitCounter.increment();
            log.debug("Cache HIT: key={}", cacheKey);

            ServerHttpResponse response = exchange.getResponse();

            // Restore status
            int status = Integer.parseInt(cached.getOrDefault(FIELD_STATUS, "200"));
            response.setStatusCode(HttpStatus.valueOf(status));

            // Restore headers
            String headersJson = cached.get(FIELD_HEADERS);
            if (headersJson != null) {
                deserializeHeaders(headersJson, response.getHeaders());
            }

            // Cache headers
            if (addCacheHeaders) {
                response.getHeaders().set("X-Cache", "HIT");
                String cachedAtStr = cached.get(FIELD_CACHED_AT);
                if (cachedAtStr != null) {
                    long cachedAtEpoch = Long.parseLong(cachedAtStr);
                    long ageSeconds = (Instant.now().toEpochMilli() - cachedAtEpoch) / 1000;
                    response.getHeaders().set("Age", String.valueOf(Math.max(0, ageSeconds)));

                    // Estimate TTL remaining
                    long ttlRemaining = ttlSeconds - ageSeconds;
                    if (ttlRemaining > 0) {
                        response.getHeaders().set("X-Cache-TTL", String.valueOf(ttlRemaining));
                    }
                }
            }

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

        // ── Cache MISS ─────────────────────────────────────────────────────────

        private Mono<Void> handleCacheMiss(ServerWebExchange exchange,
                                            GatewayFilterChain chain,
                                            String cacheKey,
                                            String routeId) {
            missCounter.increment();
            log.debug("Cache MISS: key={}", cacheKey);

            // Decorate the response to capture the body after upstream responds
            ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(exchange.getResponse()) {
                @Override
                public Mono<Void> writeWith(org.reactivestreams.Publisher<? extends DataBuffer> body) {
                    HttpStatus statusCode = HttpStatus.resolve(
                            getDelegate().getStatusCode() != null
                                    ? getDelegate().getStatusCode().value() : 200);

                    // Add MISS header
                    if (addCacheHeaders) {
                        getDelegate().getHeaders().set("X-Cache", "MISS");
                    }

                    // Check if this status code is cacheable
                    if (statusCode == null || !cacheableStatuses.contains(statusCode.value())) {
                        skipCounter.increment();
                        return super.writeWith(body);
                    }

                    // Check Cache-Control directives
                    if (respectCacheControl) {
                        String cacheControl = getDelegate().getHeaders().getFirst(HttpHeaders.CACHE_CONTROL);
                        if (cacheControl != null) {
                            String cc = cacheControl.toLowerCase(Locale.ROOT);
                            if (cc.contains("no-store") || cc.contains("private")) {
                                skipCounter.increment();
                                log.debug("Cache skip: Cache-Control={} for key={}", cacheControl, cacheKey);
                                return super.writeWith(body);
                            }
                        }
                    }

                    // Join the body to capture it
                    return DataBufferUtils.join(Flux.from(body))
                            .flatMap(dataBuffer -> {
                                byte[] bytes = new byte[dataBuffer.readableByteCount()];
                                dataBuffer.read(bytes);
                                DataBufferUtils.release(dataBuffer);

                                // Skip caching if body is too large
                                if (bytes.length > maxCachedBodySize) {
                                    skipCounter.increment();
                                    log.debug("Cache skip: body size {} exceeds max {} for key={}",
                                            bytes.length, maxCachedBodySize, cacheKey);
                                    DataBuffer outBuffer = getDelegate().bufferFactory().wrap(bytes);
                                    return super.writeWith(Mono.just(outBuffer));
                                }

                                // Determine effective TTL
                                int effectiveTtl = resolveEffectiveTtl(getDelegate().getHeaders());

                                // Store in Redis
                                Map<String, String> cacheEntry = new LinkedHashMap<>();
                                cacheEntry.put(FIELD_STATUS, String.valueOf(
                                        statusCode.value()));
                                cacheEntry.put(FIELD_HEADERS, serializeHeaders(getDelegate().getHeaders()));
                                cacheEntry.put(FIELD_BODY, Base64.getEncoder().encodeToString(bytes));
                                cacheEntry.put(FIELD_CACHED_AT, String.valueOf(Instant.now().toEpochMilli()));

                                Mono<Void> storeMono = redis.opsForHash()
                                        .putAll(cacheKey, cacheEntry)
                                        .then(redis.expire(cacheKey, Duration.ofSeconds(effectiveTtl)))
                                        .then();

                                // Write response to client and store in Redis concurrently
                                DataBuffer outBuffer = getDelegate().bufferFactory().wrap(bytes);
                                return storeMono.then(super.writeWith(Mono.just(outBuffer)));
                            });
                }
            };

            return chain.filter(exchange.mutate().response(decoratedResponse).build());
        }

        // ── TTL resolution ─────────────────────────────────────────────────────

        private int resolveEffectiveTtl(HttpHeaders headers) {
            if (!respectCacheControl) {
                return ttlSeconds;
            }

            String cacheControl = headers.getFirst(HttpHeaders.CACHE_CONTROL);
            if (cacheControl == null) {
                return ttlSeconds;
            }

            String cc = cacheControl.toLowerCase(Locale.ROOT);

            // s-maxage takes precedence for shared caches
            int sMaxAge = extractDirectiveSeconds(cc, "s-maxage");
            if (sMaxAge >= 0) {
                return Math.min(ttlSeconds, sMaxAge);
            }

            // max-age
            int maxAge = extractDirectiveSeconds(cc, "max-age");
            if (maxAge >= 0) {
                return Math.min(ttlSeconds, maxAge);
            }

            // no-cache — treat as TTL=0 (require revalidation)
            if (cc.contains("no-cache")) {
                return 0;
            }

            return ttlSeconds;
        }

        private static int extractDirectiveSeconds(String cacheControl, String directive) {
            int idx = cacheControl.indexOf(directive + "=");
            if (idx < 0) return -1;
            int start = idx + directive.length() + 1;
            int end = start;
            while (end < cacheControl.length() && Character.isDigit(cacheControl.charAt(end))) {
                end++;
            }
            if (end == start) return -1;
            try {
                return Integer.parseInt(cacheControl.substring(start, end));
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        // ── Cache key building ─────────────────────────────────────────────────

        private String buildCacheKey(String routeId, ServerWebExchange exchange) {
            var request = exchange.getRequest();
            StringBuilder keyBuilder = new StringBuilder();
            keyBuilder.append(request.getPath().value());

            // Append sorted query params
            var queryParams = request.getQueryParams();
            if (!queryParams.isEmpty()) {
                keyBuilder.append("?");
                queryParams.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(e -> e.getValue().stream().sorted().forEach(
                                v -> keyBuilder.append(e.getKey()).append("=").append(v).append("&")));
            }

            // Append vary headers if strategy requires it
            if ("PATH_QUERY_HEADERS".equals(keyStrategy) && varyHeaders.length > 0) {
                for (String header : varyHeaders) {
                    String value = request.getHeaders().getFirst(header);
                    if (value != null) {
                        keyBuilder.append("|").append(header).append("=").append(value);
                    }
                }
            }

            String derivedKey = keyBuilder.toString();
            String hashedKey = sha256(derivedKey);
            return REDIS_PREFIX + routeId + ":" + hashedKey;
        }

        private static String extractRouteId(ServerWebExchange exchange) {
            // Route ID is available from the SCG route metadata
            var route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            if (route instanceof org.springframework.cloud.gateway.route.Route r) {
                // Route ID format: tenantId::routeId — extract just routeId
                String id = r.getId();
                if (id != null && id.contains("::")) {
                    return id.substring(id.indexOf("::") + 2);
                }
                return id;
            }
            return null;
        }

        private static String sha256(String input) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder();
                for (byte b : hash) {
                    hex.append(String.format("%02x", b));
                }
                return hex.toString();
            } catch (NoSuchAlgorithmException e) {
                // SHA-256 is always available
                throw new IllegalStateException("SHA-256 not available", e);
            }
        }

        // ── Header serialization ───────────────────────────────────────────────

        private static String serializeHeaders(HttpHeaders headers) {
            // Simple serialization: key:value1,value2;key2:value
            StringBuilder sb = new StringBuilder();
            headers.forEach((name, values) -> {
                // Skip hop-by-hop and internal headers
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.equals("transfer-encoding") || lower.equals("connection")
                        || lower.equals("x-cache") || lower.equals("x-cache-ttl") || lower.equals("age")) {
                    return;
                }
                sb.append(name).append(":");
                sb.append(String.join(",", values));
                sb.append("\n");
            });
            return sb.toString();
        }

        private static void deserializeHeaders(String serialized, HttpHeaders target) {
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

    // ── Utility methods ────────────────────────────────────────────────────────

    private static Set<String> parseMethods(String methods) {
        if (methods == null || methods.isBlank()) {
            return Set.of("GET");
        }
        Set<String> result = new HashSet<>();
        for (String m : methods.split(",")) {
            result.add(m.trim().toUpperCase(Locale.ROOT));
        }
        return result;
    }

    private static Set<Integer> parseStatusCodes(String statusCodes) {
        if (statusCodes == null || statusCodes.isBlank()) {
            return Set.of(200, 206, 301);
        }
        Set<Integer> result = new HashSet<>();
        for (String s : statusCodes.split(",")) {
            try {
                result.add(Integer.parseInt(s.trim()));
            } catch (NumberFormatException e) {
                log.warn("ResponseCache: unparseable status code '{}' — skipping", s);
            }
        }
        return result;
    }

    @Data
    public static class Config {
        /** Default cache TTL in seconds. Default: 60. */
        private int ttlSeconds = 60;
        /** Max response body bytes to cache (64 KB). Default: 65536. */
        private int maxCachedBodySize = 65536;
        /** HTTP methods to cache (comma-separated). Default: GET. */
        private String methods = "GET";
        /** Status codes eligible for caching (comma-separated). Default: 200,206,301. */
        private String statusCodes = "200,206,301";
        /**
         * Cache key strategy: {@code PATH_QUERY} or {@code PATH_QUERY_HEADERS}.
         * Default: PATH_QUERY.
         */
        private String keyStrategy = "PATH_QUERY";
        /** Headers to include in cache key when {@code keyStrategy=PATH_QUERY_HEADERS}. */
        private String[] varyHeaders = {};
        /** Honour upstream {@code Cache-Control} directives. Default: true. */
        private boolean respectCacheControl = true;
        /** Inject {@code X-Cache: HIT/MISS} headers. Default: true. */
        private boolean addCacheHeaders = true;
    }
}

