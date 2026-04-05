package io.routify.gateway.filter;

import io.routify.common.web.RoutifyHeaders;
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
 *   <li>If count &lt; {@code maxRequests} → adds the current timestamp as a new member and returns 1 (allow)</li>
 *   <li>Otherwise returns 0 (reject) with the TTL in milliseconds until the oldest request expires</li>
 * </ol>
 *
 * <h3>Config params:</h3>
 * <ul>
 *   <li>{@code windowMs}    — sliding window size in milliseconds (default: 60000 = 1 min)</li>
 *   <li>{@code maxRequests} — max allowed requests per window (default: 100)</li>
 *   <li>{@code keyResolver} — IP | USER | TENANT | API_KEY | TENANT_USER (default: IP)</li>
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
     * Lua script — atomically evaluates the sliding window in Redis.
     * KEYS[1] = rate limit key
     * ARGV[1] = now (epoch ms as string)
     * ARGV[2] = window start (now - windowMs, epoch ms as string)
     * ARGV[3] = maxRequests
     * ARGV[4] = window TTL in seconds (for key expiry)
     * Returns: "1" if allowed, "0" if rejected
     */
    private static final String SLIDING_WINDOW_SCRIPT = """
            local key       = KEYS[1]
            local now       = tonumber(ARGV[1])
            local winStart  = tonumber(ARGV[2])
            local maxReqs   = tonumber(ARGV[3])
            local ttlSec    = tonumber(ARGV[4])
            redis.call('ZREMRANGEBYSCORE', key, '-inf', winStart)
            local count = redis.call('ZCARD', key)
            if count < maxReqs then
              redis.call('ZADD', key, now, now .. '-' .. math.random(1, 1000000))
              redis.call('EXPIRE', key, ttlSec)
              return 1
            end
            return 0
            """;

    private final ReactiveStringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> script;

    public SlidingWindowRateLimitGatewayFilterFactory(ReactiveStringRedisTemplate redisTemplate) {
        super(Config.class);
        this.redisTemplate = redisTemplate;
        this.script = new DefaultRedisScript<>(SLIDING_WINDOW_SCRIPT, Long.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        long windowMs    = config.getWindowMs()    > 0 ? config.getWindowMs()    : 60_000L;
        int  maxRequests = config.getMaxRequests() > 0 ? config.getMaxRequests() : 100;
        long ttlSec      = (windowMs / 1000) + 1;

        return (exchange, chain) -> {
            String key = KEY_PREFIX + resolveKey(exchange, config.getKeyResolver());
            long now      = Instant.now().toEpochMilli();
            long winStart = now - windowMs;

            return redisTemplate.execute(script,
                            List.of(key),
                            String.valueOf(now),
                            String.valueOf(winStart),
                            String.valueOf(maxRequests),
                            String.valueOf(ttlSec))
                    .next()
                    .defaultIfEmpty(1L)
                    .flatMap(result -> {
                        if (result != null && result == 1L) {
                            log.debug("SlidingWindowRateLimit: ALLOW key={}", key);
                            return chain.filter(exchange);
                        }
                        log.debug("SlidingWindowRateLimit: REJECT key={} window={}ms max={}", key, windowMs, maxRequests);
                        return tooManyRequests(exchange, windowMs);
                    });
        };
    }

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

    private Mono<Void> tooManyRequests(ServerWebExchange exchange, long windowMs) {
        ServerHttpResponse resp = exchange.getResponse();
        resp.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        resp.getHeaders().set("Content-Type", "application/problem+json");
        resp.getHeaders().set("X-RateLimit-Window", windowMs + "ms");
        String body = """
                {"type":"about:blank","title":"Too Many Requests","status":429,\
                "detail":"Rate limit exceeded. Please slow down."}""";
        return resp.writeWith(Mono.just(resp.bufferFactory().wrap(body.getBytes())));
    }

    @Data
    public static class Config {
        /** Sliding window size in milliseconds. Default: 60000 (1 minute). */
        private long   windowMs    = 60_000L;
        /** Maximum requests allowed within the window. Default: 100. */
        private int    maxRequests = 100;
        /** Key resolver strategy: IP | USER | TENANT | API_KEY | TENANT_USER. Default: IP. */
        private String keyResolver = "IP";
    }
}

