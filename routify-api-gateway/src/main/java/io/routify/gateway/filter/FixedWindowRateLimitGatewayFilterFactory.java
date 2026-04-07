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
 * Gateway filter factory implementing a <strong>fixed-window rate limiter</strong>
 * backed by Redis INCR + PEXPIRE.
 *
 * <h3>Algorithm</h3>
 * For each request a Lua script atomically:
 * <ol>
 *   <li>Increments a counter key whose name encodes the current window slot
 *       ({@code now / windowMs})</li>
 *   <li>On the first increment sets the key TTL to the window size (so the counter
 *       automatically expires at the end of the window)</li>
 *   <li>If the counter &le; {@code maxRequests} → allows the request</li>
 *   <li>Otherwise rejects with HTTP 429</li>
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
 *   <li>{@code windowMs}       — fixed window size in milliseconds (default: 60000 = 1 min)</li>
 *   <li>{@code maxRequests}    — max requests allowed per window (default: 100)</li>
 *   <li>{@code keyResolver}    — IP | USER | TENANT | API_KEY | TENANT_USER (default: IP)</li>
 *   <li>{@code includeHeaders} — whether to emit X-RateLimit-* headers (default: true)</li>
 * </ul>
 *
 * <p>Filter type: {@code RATE_LIMIT_FIXED_WINDOW}
 */
@Slf4j
@Component
public class FixedWindowRateLimitGatewayFilterFactory
        extends AbstractGatewayFilterFactory<FixedWindowRateLimitGatewayFilterFactory.Config> {

    private static final String KEY_PREFIX = "routify:rl:fixed:";

    /**
     * Lua script — atomically increments the window counter, sets TTL on first use,
     * and returns a {@code {count, ttl}} tuple.
     * <p>
     * KEYS[1] = rate limit key (already includes the window slot)<br>
     * ARGV[1] = maxRequests<br>
     * ARGV[2] = window TTL in milliseconds<br>
     * Returns: {@code {current_count, remaining_ttl_seconds}}
     */
    private static final String FIXED_WINDOW_SCRIPT = """
            local key      = KEYS[1]
            local maxReqs  = tonumber(ARGV[1])
            local ttlMs    = tonumber(ARGV[2])
            local current  = redis.call('INCR', key)
            if current == 1 then
              redis.call('PEXPIRE', key, ttlMs)
            end
            local ttl = redis.call('PTTL', key)
            if ttl < 0 then ttl = ttlMs end
            return {current, math.ceil(ttl / 1000)}
            """;

    private final ReactiveStringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> script;
    private final RateLimitKeyResolver keyResolver;

    public FixedWindowRateLimitGatewayFilterFactory(ReactiveStringRedisTemplate redisTemplate,
                                                    RateLimitKeyResolver keyResolver) {
        super(Config.class);
        this.redisTemplate = redisTemplate;
        this.keyResolver = keyResolver;
        this.script = new DefaultRedisScript<>(FIXED_WINDOW_SCRIPT, List.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        long windowMs       = config.getWindowMs()    > 0 ? config.getWindowMs()    : 60_000L;
        int  maxRequests    = config.getMaxRequests() > 0 ? config.getMaxRequests() : 100;
        boolean includeHdrs = config.isIncludeHeaders();

        return (exchange, chain) -> {
            String clientKey  = keyResolver.resolve(exchange, config.getKeyResolver());
            long   windowSlot = System.currentTimeMillis() / windowMs;
            String key        = KEY_PREFIX + clientKey + ":" + windowSlot;

            return redisTemplate.execute(script,
                            List.of(key),
                            String.valueOf(maxRequests),
                            String.valueOf(windowMs))
                    .next()
                    .defaultIfEmpty(List.of(1L, Math.max(1, windowMs / 1000)))
                    .flatMap(tuple -> {
                        long count = tuple.size() > 0 ? ((Number) tuple.get(0)).longValue() : 1L;
                        long ttlSeconds = tuple.size() > 1 ? ((Number) tuple.get(1)).longValue() : Math.max(1, windowMs / 1000);

                        if (count <= maxRequests) {
                            log.debug("FixedWindowRateLimit: ALLOW key={} count={}/{}", key, count, maxRequests);
                            if (includeHdrs) {
                                injectRateLimitHeaders(exchange, maxRequests, count, ttlSeconds);
                            }
                            return chain.filter(exchange);
                        }
                        log.debug("FixedWindowRateLimit: REJECT key={} count={}/{}", key, count, maxRequests);
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
        /** Fixed window size in milliseconds. Default: 60000 (1 minute). */
        private long   windowMs    = 60_000L;
        /** Maximum requests allowed per window. Default: 100. */
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

