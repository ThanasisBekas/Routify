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
 * <h3>Config params:</h3>
 * <ul>
 *   <li>{@code windowMs}    — fixed window size in milliseconds (default: 60000 = 1 min)</li>
 *   <li>{@code maxRequests} — max requests allowed per window (default: 100)</li>
 *   <li>{@code keyResolver} — IP | USER | TENANT | API_KEY | TENANT_USER (default: IP)</li>
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
     * Lua script — atomically increments the window counter and sets TTL on first use.
     * KEYS[1] = rate limit key (already includes the window slot)
     * ARGV[1] = maxRequests
     * ARGV[2] = window TTL in milliseconds
     * Returns: current counter value (caller checks against maxRequests)
     */
    private static final String FIXED_WINDOW_SCRIPT = """
            local key      = KEYS[1]
            local maxReqs  = tonumber(ARGV[1])
            local ttlMs    = tonumber(ARGV[2])
            local current  = redis.call('INCR', key)
            if current == 1 then
              redis.call('PEXPIRE', key, ttlMs)
            end
            return current
            """;

    private final ReactiveStringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> script;

    public FixedWindowRateLimitGatewayFilterFactory(ReactiveStringRedisTemplate redisTemplate) {
        super(Config.class);
        this.redisTemplate = redisTemplate;
        this.script = new DefaultRedisScript<>(FIXED_WINDOW_SCRIPT, Long.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        long windowMs    = config.getWindowMs()    > 0 ? config.getWindowMs()    : 60_000L;
        int  maxRequests = config.getMaxRequests() > 0 ? config.getMaxRequests() : 100;

        return (exchange, chain) -> {
            String clientKey  = resolveKey(exchange, config.getKeyResolver());
            long   windowSlot = System.currentTimeMillis() / windowMs;
            String key        = KEY_PREFIX + clientKey + ":" + windowSlot;

            return redisTemplate.execute(script,
                            List.of(key),
                            String.valueOf(maxRequests),
                            String.valueOf(windowMs))
                    .next()
                    .defaultIfEmpty(1L)
                    .flatMap(count -> {
                        if (count != null && count <= maxRequests) {
                            log.debug("FixedWindowRateLimit: ALLOW key={} count={}/{}", key, count, maxRequests);
                            return chain.filter(exchange);
                        }
                        log.debug("FixedWindowRateLimit: REJECT key={} count={}/{}", key, count, maxRequests);
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
        /** Fixed window size in milliseconds. Default: 60000 (1 minute). */
        private long   windowMs    = 60_000L;
        /** Maximum requests allowed per window. Default: 100. */
        private int    maxRequests = 100;
        /** Key resolver strategy: IP | USER | TENANT | API_KEY | TENANT_USER. Default: IP. */
        private String keyResolver = "IP";
    }
}

