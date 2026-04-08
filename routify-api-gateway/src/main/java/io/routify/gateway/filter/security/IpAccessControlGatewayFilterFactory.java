package io.routify.gateway.filter.security;

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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Gateway filter factory that evaluates the client IP address against configurable
 * allowlists and denylists. Supports individual IPs, CIDR ranges (IPv4 and IPv6),
 * and {@code X-Forwarded-For} header parsing for deployments behind load balancers.
 *
 * <p>This is an order-first filter ({@code order = -1500}) — it executes before
 * all authentication filters to provide IP-based access control as the first line
 * of defense.
 *
 * <h3>Modes:</h3>
 * <ul>
 *   <li>{@code DENYLIST} (default) — block if client IP matches any address in the list</li>
 *   <li>{@code ALLOWLIST} — block if client IP does NOT match any address in the list</li>
 * </ul>
 *
 * <h3>Config params:</h3>
 * <table>
 *   <tr><th>Param</th><th>Type</th><th>Default</th><th>Description</th></tr>
 *   <tr><td>mode</td><td>ALLOWLIST | DENYLIST</td><td>DENYLIST</td><td>Filter mode</td></tr>
 *   <tr><td>addresses</td><td>String</td><td>""</td><td>Comma-separated IPs and CIDR ranges</td></tr>
 *   <tr><td>trustProxy</td><td>boolean</td><td>true</td><td>Resolve client IP from X-Forwarded-For</td></tr>
 *   <tr><td>proxyDepth</td><td>int</td><td>1</td><td>Which X-Forwarded-For entry to use (1 = rightmost)</td></tr>
 *   <tr><td>rejectStatus</td><td>int</td><td>403</td><td>HTTP status for rejected requests</td></tr>
 *   <tr><td>rejectMessage</td><td>String</td><td>"Access denied"</td><td>Error detail message</td></tr>
 * </table>
 *
 * <p>Filter type: {@code IP_ACCESS_CONTROL}
 *
 * @see CidrMatcher
 */
@Slf4j
@Component
public class IpAccessControlGatewayFilterFactory
        extends AbstractGatewayFilterFactory<IpAccessControlGatewayFilterFactory.Config> {

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private final MeterRegistry meterRegistry;

    public IpAccessControlGatewayFilterFactory(MeterRegistry meterRegistry) {
        super(Config.class);
        this.meterRegistry = meterRegistry;
    }

    @Override
    public GatewayFilter apply(Config config) {
        // Parse mode
        boolean isAllowlist = "ALLOWLIST".equalsIgnoreCase(config.getMode());

        // Compile CIDR matchers once at config bind time — NOT per-request
        List<CidrMatcher> matchers = compileMatchers(config.getAddresses());

        HttpStatus rejectStatus;
        try {
            rejectStatus = HttpStatus.valueOf(config.getRejectStatus());
        } catch (IllegalArgumentException e) {
            log.warn("IpAccessControl: invalid rejectStatus '{}' — defaulting to 403", config.getRejectStatus());
            rejectStatus = HttpStatus.FORBIDDEN;
        }

        String rejectMessage = config.getRejectMessage() != null && !config.getRejectMessage().isBlank()
                ? config.getRejectMessage() : "Access denied";

        boolean trustProxy = config.isTrustProxy();
        int proxyDepth = Math.max(1, config.getProxyDepth());

        final HttpStatus finalRejectStatus = rejectStatus;

        log.info("IpAccessControl filter configured: mode={} addresses={} trustProxy={} proxyDepth={}",
                isAllowlist ? "ALLOWLIST" : "DENYLIST", matchers.size(), trustProxy, proxyDepth);

        return new IpAccessControlGatewayFilter(
                isAllowlist, matchers, trustProxy, proxyDepth,
                finalRejectStatus, rejectMessage, meterRegistry);
    }

    /**
     * Parses a comma-separated list of IP addresses and CIDR ranges into compiled
     * {@link CidrMatcher} instances. Invalid entries are logged and skipped.
     */
    private static List<CidrMatcher> compileMatchers(String addresses) {
        if (addresses == null || addresses.isBlank()) {
            return List.of();
        }
        List<CidrMatcher> matchers = new ArrayList<>();
        for (String entry : addresses.split(",")) {
            String trimmed = entry.strip();
            if (trimmed.isEmpty()) continue;
            try {
                matchers.add(CidrMatcher.parse(trimmed));
            } catch (IllegalArgumentException e) {
                log.warn("IpAccessControl: invalid address/CIDR '{}' — skipping: {}", trimmed, e.getMessage());
            }
        }
        return List.copyOf(matchers);
    }

    /**
     * Inner filter implementing {@link Ordered} with order {@code -1500} — before
     * all authentication filters (which run at {@code -1000}).
     */
    static final class IpAccessControlGatewayFilter implements GatewayFilter, Ordered {

        /** Runs before all auth filters (-1000) and correlation ID (-1000). */
        private static final int FILTER_ORDER = -1500;

        private final boolean isAllowlist;
        private final List<CidrMatcher> matchers;
        private final boolean trustProxy;
        private final int proxyDepth;
        private final HttpStatus rejectStatus;
        private final String rejectMessage;
        private final MeterRegistry meterRegistry;

        IpAccessControlGatewayFilter(boolean isAllowlist,
                                     List<CidrMatcher> matchers,
                                     boolean trustProxy,
                                     int proxyDepth,
                                     HttpStatus rejectStatus,
                                     String rejectMessage,
                                     MeterRegistry meterRegistry) {
            this.isAllowlist = isAllowlist;
            this.matchers = matchers;
            this.trustProxy = trustProxy;
            this.proxyDepth = proxyDepth;
            this.rejectStatus = rejectStatus;
            this.rejectMessage = rejectMessage;
            this.meterRegistry = meterRegistry;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            InetAddress clientIp = resolveClientIp(exchange);
            if (clientIp == null) {
                log.warn("IpAccessControl: unable to resolve client IP — rejecting request");
                return reject(exchange);
            }

            // Normalize IPv4-mapped IPv6
            clientIp = CidrMatcher.normalize(clientIp);

            boolean matchesAny = CidrMatcher.matchesAny(clientIp, matchers);
            String routeId = extractRouteId(exchange);

            if (isAllowlist) {
                // ALLOWLIST: block if NOT matched (empty list blocks all)
                if (!matchesAny) {
                    log.debug("IpAccessControl ALLOWLIST: {} not in allowlist — blocking (route={})",
                            clientIp.getHostAddress(), routeId);
                    incrementBlocked(routeId);
                    return reject(exchange);
                }
            } else {
                // DENYLIST: block if matched (empty list allows all)
                if (matchesAny) {
                    log.debug("IpAccessControl DENYLIST: {} in denylist — blocking (route={})",
                            clientIp.getHostAddress(), routeId);
                    incrementBlocked(routeId);
                    return reject(exchange);
                }
            }

            log.debug("IpAccessControl: {} allowed (route={})", clientIp.getHostAddress(), routeId);
            incrementAllowed(routeId);
            return chain.filter(exchange);
        }

        @Override
        public int getOrder() {
            return FILTER_ORDER;
        }

        /**
         * Resolves the client IP address. When {@code trustProxy=true}, parses the
         * {@code X-Forwarded-For} header and uses the entry at {@code proxyDepth}
         * from the right. Otherwise, uses the remote address from the connection.
         */
        private InetAddress resolveClientIp(ServerWebExchange exchange) {
            if (trustProxy) {
                String xff = exchange.getRequest().getHeaders().getFirst(X_FORWARDED_FOR);
                if (xff != null && !xff.isBlank()) {
                    String[] parts = xff.split(",");
                    // proxyDepth=1 → rightmost (the last proxy hop)
                    int idx = parts.length - proxyDepth;
                    if (idx >= 0 && idx < parts.length) {
                        String ip = parts[idx].strip();
                        try {
                            return InetAddress.getByName(ip);
                        } catch (UnknownHostException e) {
                            log.warn("IpAccessControl: invalid IP in X-Forwarded-For: {}", ip);
                        }
                    }
                }
            }

            // Fallback to connection remote address
            InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
            return remoteAddress != null ? remoteAddress.getAddress() : null;
        }

        private Mono<Void> reject(ServerWebExchange exchange) {
            return GatewayProblemResponse.status(rejectStatus)
                    .errorCode("IP_ACCESS_DENIED")
                    .detail(rejectMessage)
                    .write(exchange);
        }

        private String extractRouteId(ServerWebExchange exchange) {
            var routeAttr = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            if (routeAttr instanceof org.springframework.cloud.gateway.route.Route route) {
                return route.getId();
            }
            return "unknown";
        }

        private void incrementAllowed(String routeId) {
            Counter.builder("routify.filter.ip_access_control.allowed")
                    .tag("routeId", routeId)
                    .register(meterRegistry)
                    .increment();
        }

        private void incrementBlocked(String routeId) {
            Counter.builder("routify.filter.ip_access_control.blocked")
                    .tag("routeId", routeId)
                    .register(meterRegistry)
                    .increment();
        }
    }

    @Data
    public static class Config {
        /** Filter mode: ALLOWLIST or DENYLIST. Default: DENYLIST. */
        private String mode = "DENYLIST";
        /** Comma-separated IP addresses and CIDR ranges. */
        private String addresses = "";
        /** Whether to resolve client IP from X-Forwarded-For header. */
        private boolean trustProxy = true;
        /** Which X-Forwarded-For entry to use (1 = rightmost proxy hop). */
        private int proxyDepth = 1;
        /** HTTP status code for rejected requests. Default: 403. */
        private int rejectStatus = 403;
        /** Error detail message for rejected requests. */
        private String rejectMessage = "Access denied";
    }
}

