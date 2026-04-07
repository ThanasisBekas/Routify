package io.routify.gateway.filter.ratelimit;

import io.routify.common.web.RoutifyHeaders;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetSocketAddress;
import java.util.Optional;

/**
 * Shared rate-limit key resolver used by all three rate limiter paths:
 * <ul>
 *   <li>{@code FixedWindowRateLimitGatewayFilterFactory}</li>
 *   <li>{@code SlidingWindowRateLimitGatewayFilterFactory}</li>
 *   <li>SCG built-in {@code RequestRateLimiter} (token bucket) via {@code KeyResolver} beans</li>
 * </ul>
 *
 * <h3>Supported strategies:</h3>
 * <ul>
 *   <li>{@code IP} — client IP address (X-Forwarded-For aware)</li>
 *   <li>{@code USER} — authenticated user ID from X-Auth-User-Id header</li>
 *   <li>{@code TENANT} — tenant ID from X-Tenant-Id header</li>
 *   <li>{@code API_KEY} — API key from X-Api-Key header (or {@code apiKey} query param)</li>
 *   <li>{@code TENANT_USER} — composite tenantId:userId</li>
 *   <li>{@code ROUTE} — route ID from the exchange's matched route attribute</li>
 *   <li>{@code HEADER:<name>} — value of an arbitrary header</li>
 *   <li>{@code COMPOSITE:<a>:<b>} — concatenation of two strategies separated by {@code :}</li>
 * </ul>
 *
 * <p>When a strategy yields a null or missing value, the resolver falls back to the client IP.
 */
@Slf4j
@Component
public class RateLimitKeyResolver {

    /**
     * Resolve a rate-limit key from the exchange based on the configured strategy.
     *
     * @param exchange  the current server web exchange
     * @param strategy  the key resolution strategy (case-insensitive)
     * @return a non-null key string
     */
    public String resolve(ServerWebExchange exchange, String strategy) {
        String normalized = strategy != null ? strategy.trim().toUpperCase() : "IP";
        String result = doResolve(exchange, normalized);
        if (result == null || result.isBlank()) {
            log.debug("RateLimitKeyResolver: strategy '{}' yielded null/blank — falling back to client IP", strategy);
            result = resolveIp(exchange);
        }
        return result;
    }

    private String doResolve(ServerWebExchange exchange, String strategy) {
        // ─── COMPOSITE:<a>:<b> — must check before general cases ──────────
        if (strategy.startsWith("COMPOSITE:")) {
            return resolveComposite(exchange, strategy);
        }

        // ─── HEADER:<name> ────────────────────────────────────────────────
        if (strategy.startsWith("HEADER:")) {
            return resolveHeader(exchange, strategy);
        }

        // ─── Simple strategies ────────────────────────────────────────────
        return switch (strategy) {
            case "IP" -> resolveIp(exchange);
            case "USER" -> resolveUser(exchange);
            case "TENANT" -> resolveTenant(exchange);
            case "API_KEY" -> resolveApiKey(exchange);
            case "TENANT_USER" -> resolveTenantUser(exchange);
            case "ROUTE" -> resolveRoute(exchange);
            default -> {
                log.warn("RateLimitKeyResolver: unknown strategy '{}' — falling back to IP", strategy);
                yield resolveIp(exchange);
            }
        };
    }

    // ─── Strategy implementations ─────────────────────────────────────────────

    private String resolveIp(ServerWebExchange exchange) {
        // X-Forwarded-For aware: use first value if present
        String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // Take the first (leftmost) entry — the original client IP
            String clientIp = xff.split(",")[0].trim();
            if (!clientIp.isBlank()) {
                return clientIp;
            }
        }
        return Optional.ofNullable(exchange.getRequest().getRemoteAddress())
                .map(InetSocketAddress::getHostString)
                .orElse("unknown");
    }

    private String resolveUser(ServerWebExchange exchange) {
        return Optional.ofNullable(
                        exchange.getRequest().getHeaders().getFirst(RoutifyHeaders.AUTH_USER_ID))
                .map(u -> "user:" + u)
                .orElse("anonymous");
    }

    private String resolveTenant(ServerWebExchange exchange) {
        return Optional.ofNullable(
                        exchange.getRequest().getHeaders().getFirst(RoutifyHeaders.TENANT_ID))
                .map(t -> "tenant:" + t)
                .orElse("unknown-tenant");
    }

    private String resolveApiKey(ServerWebExchange exchange) {
        String k = exchange.getRequest().getHeaders().getFirst(RoutifyHeaders.API_KEY);
        if (k == null) {
            k = exchange.getRequest().getQueryParams().getFirst("apiKey");
        }
        return k != null ? "apikey:" + k.hashCode() : "no-key";
    }

    private String resolveTenantUser(ServerWebExchange exchange) {
        String t = exchange.getRequest().getHeaders().getFirst(RoutifyHeaders.TENANT_ID);
        String u = exchange.getRequest().getHeaders().getFirst(RoutifyHeaders.AUTH_USER_ID);
        return "%s:%s".formatted(
                t != null ? t : "unknown",
                u != null ? u : "anonymous");
    }

    private String resolveRoute(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route != null) {
            return "route:" + route.getId();
        }
        return null; // will trigger IP fallback
    }

    private String resolveHeader(ServerWebExchange exchange, String strategy) {
        // HEADER:<headerName>
        String headerName = strategy.substring("HEADER:".length()).trim();
        if (headerName.isBlank()) {
            log.warn("RateLimitKeyResolver: HEADER strategy with empty header name");
            return null;
        }
        String value = exchange.getRequest().getHeaders().getFirst(headerName);
        return value != null ? "header:" + headerName + ":" + value : null;
    }

    private String resolveComposite(ServerWebExchange exchange, String strategy) {
        // COMPOSITE:<strategyA>:<strategyB>
        // Split on the first two colons after "COMPOSITE:"
        String remainder = strategy.substring("COMPOSITE:".length());
        int colonIdx = remainder.indexOf(':');
        if (colonIdx <= 0 || colonIdx >= remainder.length() - 1) {
            log.warn("RateLimitKeyResolver: invalid COMPOSITE strategy '{}' — expected COMPOSITE:<a>:<b>", strategy);
            return null;
        }
        String strategyA = remainder.substring(0, colonIdx).trim();
        String strategyB = remainder.substring(colonIdx + 1).trim();
        String keyA = doResolve(exchange, strategyA);
        String keyB = doResolve(exchange, strategyB);
        // If either part is null, fall back individually to IP
        if (keyA == null || keyA.isBlank()) keyA = resolveIp(exchange);
        if (keyB == null || keyB.isBlank()) keyB = resolveIp(exchange);
        return keyA + ":" + keyB;
    }
}

