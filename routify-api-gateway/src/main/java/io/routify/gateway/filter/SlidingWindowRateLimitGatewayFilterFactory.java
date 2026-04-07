package io.routify.gateway.filter;

import io.routify.common.web.RoutifyHeaders;
import io.routify.gateway.filter.ratelimit.RateLimitKeyResolver;
import io.routify.gateway.filter.shared.GatewayProblemResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Gateway filter factory implementing a <strong>sliding-window rate limiter</strong>
 * backed by Redis sorted sets.
 *
 * <h3>Algorithm</h3>
 * For each request a Lua script atomically:
 * <ol>
 *   <li>Removes scored members older than {@code now - windowMs} (outside the window)</li>
 *   <li>Counts remaining members (requests in the current window)</li>
 *   <li>If count &lt; {@code maxRequests} → adds the current timestamp as a new member and returns allowed=1</li>
 *   <li>Otherwise returns allowed=0</li>
 * </ol>
 *
 * <h3>Response headers (GF-03)</h3>
 * On every response (allow <em>and</em> reject) the filter injects standard rate
 * limit headers:
 * <ul>
 *   <li>{@code X-RateLimit-Limit}     — the window's maximum allowed requests</li>
 *   <li>{@code X-RateLimit-Remaining} — remaining requests before throttling</li>
 *   <li>{@code X-RateLimit-Reset}     — epoch-second timestamp when the window resets</li>
 * </ul>
 * On 429 rejection the {@code Retry-After} header is <em>always</em> included,
 * even when {@code includeHeaders=false} (per RFC 6585).
 *
 * <h3>Config params:</h3>
 * <ul>
 *   <li>{@code windowMs}       — sliding window size in milliseconds (default: 60000 = 1 min)</li>
 *   <li>{@code maxRequests}    — max allowed requests per window (default: 100)</li>
 *   <li>{@code keyResolver}    — IP | USER | TENANT | API_KEY | TENANT_USER (default: IP)</li>
 *   <li>{@code includeHeaders} — whether to emit X-RateLimit-* headers (default: true)</li>
 * </ul>
 *
 * <p>Filter type: {@code RATE_LIMIT_SLIDING_WINDOW}
 */
@Slf4j
@Component
public class SlidingWindowRateLimitGatewayFilterFactory
        extends AbstractGatewayFilterFactory<SlidingWindowRateLimitGatewayFilterFactory.Config> {

    private static final String KEY_PREFIX = "routify:rl:sliding:";

    /**
     * Lua script — atomically evaluates the sliding window in Redis and returns
     * a {@code {allowed, count, ttl}} tuple.
     * <p>
     * KEYS[1] = rate limit key<br>
     * ARGV[1] = now (epoch ms as string)<br>
     * ARGV[2] = window start (now - windowMs, epoch ms as string)<br>
     * ARGV[3] = maxRequests<br>
     * ARGV[4] = window TTL in seconds (for key expiry)<br>
     * ARGV[5] = windowMs (for TTL fallback)<br>
     * Returns: {@code {allowed (1/0), current_count, remaining_ttl_seconds}}
     */
    private static final String SLIDING_WINDOW_SCRIPT = """
            local key       = KEYS[1]
            local now       = tonumber(ARGV[1])
            local winStart  = tonumber(ARGV[2])
            local maxReqs   = tonumber(ARGV[3])
            local ttlSec    = tonumber(ARGV[4])
            local windowMs  = tonumber(ARGV[5])
            redis.call('ZREMRANGEBYSCORE', key, '-inf', winStart)
            local count = redis.call('ZCARD', key)
            if count < maxReqs then
              redis.call('ZADD', key, now, now .. '-' .. math.random(1, 1000000))
              redis.call('EXPIRE', key, ttlSec)
              count = count + 1
              local ttl = redis.call('PTTL', key)
              if ttl < 0 then ttl = windowMs end
              return {1, count, math.ceil(ttl / 1000)}
            end
            local ttl = redis.call('PTTL', key)
            if ttl < 0 then ttl = windowMs end
            return {0, count, math.ceil(ttl / 1000)}
            """;

    private final ReactiveStringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> script;
    private final RateLimitKeyResolver keyResolver;

    public SlidingWindowRateLimitGatewayFilterFactory(ReactiveStringRedisTemplate redisTemplate,
                                                      RateLimitKeyResolver keyResolver) {
        super(Config.class);
        this.redisTemplate = redisTemplate;
        this.keyResolver = keyResolver;
        this.script = new DefaultRedisScript<>(SLIDING_WINDOW_SCRIPT, List.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        long windowMs       = config.getWindowMs()    > 0 ? config.getWindowMs()    : 60_000L;
        int  maxRequests    = config.getMaxRequests() > 0 ? config.getMaxRequests() : 100;
        long ttlSec         = (windowMs / 1000) + 1;
        boolean includeHdrs = config.isIncludeHeaders();

        return (exchange, chain) -> {
            String key = KEY_PREFIX + keyResolver.resolve(exchange, config.getKeyResolver());
            long now      = Instant.now().toEpochMilli();
            long winStart = now - windowMs;

            return redisTemplate.execute(script,
                            List.of(key),
                            String.valueOf(now),
                            String.valueOf(winStart),
                            String.valueOf(maxRequests),
                            String.valueOf(ttlSec),
                            String.valueOf(windowMs))
                    .next()
                    .defaultIfEmpty(List.of(1L, 0L, Math.max(1, windowMs / 1000)))
                    .flatMap(tuple -> {
                        long allowed    = tuple.size() > 0 ? ((Number) tuple.get(0)).longValue() : 1L;
                        long count      = tuple.size() > 1 ? ((Number) tuple.get(1)).longValue() : 0L;
                        long ttlSeconds = tuple.size() > 2 ? ((Number) tuple.get(2)).longValue() : Math.max(1, windowMs / 1000);

                        if (allowed == 1L) {
                            log.debug("SlidingWindowRateLimit: ALLOW key={} count={}/{}", key, count, maxRequests);
                            if (includeHdrs) {
                                injectRateLimitHeaders(exchange, maxRequests, count, ttlSeconds);
                            }
                            return chain.filter(exchange);
                        }
                        log.debug("SlidingWindowRateLimit: REJECT key={} count={}/{} window={}ms", key, count, maxRequests, windowMs);
                        return tooManyRequests(exchange, maxRequests, count, ttlSeconds, includeHdrs);
                    });
        };
    }

    /**
     * Injects standard {@code X-RateLimit-*} response headers.
     */
    private void injectRateLimitHeaders(ServerWebExchange exchange,
                                        int maxRequests, long count, long ttlSeconds) {
        ServerHttpResponse response = exchange.getResponse();
        long remaining = Math.max(0, maxRequests - count);
        long resetEpoch = Instant.now().plusSeconds(ttlSeconds).getEpochSecond();

        response.getHeaders().set("X-RateLimit-Limit", String.valueOf(maxRequests));
        response.getHeaders().set("X-RateLimit-Remaining", String.valueOf(remaining));
        response.getHeaders().set("X-RateLimit-Reset", String.valueOf(resetEpoch));
    }

    /**
     * @deprecated Use {@link RateLimitKeyResolver#resolve(ServerWebExchange, String)} instead.
     *             Will be removed in the next minor version.
     */
    @Deprecated(forRemoval = true)
    private String resolveKey(ServerWebExchange exchange, String keyResolver) {
        return switch (keyResolver != null ? keyResolver.toUpperCase() : "IP") {
            case "USER"        -> Optional.ofNullable(
                    exchange.getRequest().getHeaders().getFirst(RoutifyHeaders.AUTH_USER_ID))
                    .map(u -> "user:" + u).orElse("anonymous");
            case "TENANT"      -> Optional.ofNullable(
                    exchange.getRequest().getHeaders().getFirst(RoutifyHeaders.TENANT_ID))
                    .map(t -> "tenant:" + t).orElse("unknown-tenant");
            case "API_KEY"     -> {
                String k = exchange.getRequest().getHeaders().getFirst(RoutifyHeaders.API_KEY);
                if (k == null) k = exchange.getRequest().getQueryParams().getFirst("apiKey");
                yield k != null ? "apikey:" + k.hashCode() : "no-key";
            }
            case "TENANT_USER" -> {
                String t = exchange.getRequest().getHeaders().getFirst(RoutifyHeaders.TENANT_ID);
                String u = exchange.getRequest().getHeaders().getFirst(RoutifyHeaders.AUTH_USER_ID);
                yield "%s:%s".formatted(
                        t != null ? t : "unknown",
                        u != null ? u : "anonymous");
            }
            default -> Optional.ofNullable(exchange.getRequest().getRemoteAddress())
                    .map(InetSocketAddress::getHostString)
                    .orElse("unknown");
        };
    }

    private Mono<Void> tooManyRequests(ServerWebExchange exchange,
                                       int maxRequests, long count, long ttlSeconds,
                                       boolean includeHeaders) {
        long retryAfterSeconds = Math.max(1, ttlSeconds);
        var builder = GatewayProblemResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                .errorCode("RATE_LIMIT_EXCEEDED")
                .detail("Rate limit exceeded. Please slow down.")
                .header("Retry-After", retryAfterSeconds);

        if (includeHeaders) {
            long remaining = Math.max(0, maxRequests - count);
            long resetEpoch = Instant.now().plusSeconds(ttlSeconds).getEpochSecond();
            builder.header("X-RateLimit-Limit", String.valueOf(maxRequests))
                   .header("X-RateLimit-Remaining", String.valueOf(remaining))
                   .header("X-RateLimit-Reset", String.valueOf(resetEpoch));
        }

        return builder.write(exchange);
    }

    @Data
    public static class Config {
        /** Sliding window size in milliseconds. Default: 60000 (1 minute). */
        private long   windowMs    = 60_000L;
        /** Maximum requests allowed within the window. Default: 100. */
        private int    maxRequests = 100;
        /** Key resolver strategy: IP | USER | TENANT | API_KEY | TENANT_USER. Default: IP. */
        private String keyResolver = "IP";
        /**
         * Whether to inject {@code X-RateLimit-Limit}, {@code X-RateLimit-Remaining},
         * {@code X-RateLimit-Reset} headers on every response. Default: true.
         * <p>{@code Retry-After} on 429 responses is always included regardless of this flag.
         */
        private boolean includeHeaders = true;
    }
}
