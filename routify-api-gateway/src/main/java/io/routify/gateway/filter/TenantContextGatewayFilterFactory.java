package io.routify.gateway.filter;

import io.routify.common.domain.TenantPlan;
import io.routify.common.observability.RoutifyMetrics;
import io.routify.common.security.RedisKeys;
import io.routify.common.web.RoutifyHeaders;
import io.routify.gateway.config.GatewayConfigLoader;
import io.routify.gateway.routing.RouteDefinitionBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * Gateway filter factory that propagates and validates tenant context through the request.
 *
 * <p>Runs <em>after</em> {@link JwtAuthGatewayFilterFactory} (order +100) so that
 * {@code X-Auth-Tenant-Id} — which is written by the JWT filter — is already present
 * on the request when this filter executes.
 *
 * <h3>Behaviour — controlled by the {@code enabled} flag</h3>
 *
 * <h4>{@code enabled=true} — caller provides the tenant</h4>
 * <p>The caller is expected to supply {@code X-Tenant-Id} (or it is derived from
 * the JWT {@code X-Auth-Tenant-Id} claim). Routes include a
 * {@code Header=X-Tenant-Id} predicate so only matching requests can reach the
 * route. Cross-validation between the JWT claim and the caller-supplied header
 * rejects mismatches with {@code 403 Forbidden}.</p>
 *
 * <h4>{@code enabled=false} — auto-inject from route metadata</h4>
 * <p>Callers do <em>not</em> supply the header. The filter auto-injects the route
 * owner's tenant ID from route metadata so that subsequent filters in the chain
 * (rate limiters, request logger, etc.) can still resolve tenant context from
 * the header. <strong>When auto-inject mode is active, the {@code TENANT_CONTEXT}
 * filter must be present in the route's filter chain</strong> — otherwise downstream
 * filters will not see any tenant context.</p>
 *
 * <p>In both modes the resolved {@code X-Tenant-Id} header is <strong>always</strong>
 * forwarded to the upstream destination.</p>
 *
 * <p>The resolved canonical tenant ID is also stored as an exchange attribute
 * ({@code routify.tenantId}) for future-proof access by other filters.
 *
 * <p>Filter type: {@code TENANT_CONTEXT}
 */
@Slf4j
@Component
public class TenantContextGatewayFilterFactory
        extends AbstractGatewayFilterFactory<TenantContextGatewayFilterFactory.Config> {

    /** Exchange attribute key for the resolved canonical tenant ID. */
    public static final String ATTR_TENANT_ID = "routify.tenantId";

    private final GatewayConfigLoader configLoader;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final GatewayTenantPlanCache tenantPlanCache;
    private final RoutifyMetrics metrics;
    private final boolean quotaEnabled;

    public TenantContextGatewayFilterFactory(GatewayConfigLoader configLoader,
                                             ReactiveStringRedisTemplate redisTemplate,
                                             GatewayTenantPlanCache tenantPlanCache,
                                             RoutifyMetrics metrics,
                                             @Value("${routify.gateway.quota.enabled:true}") boolean quotaEnabled) {
        super(Config.class);
        this.configLoader = configLoader;
        this.redisTemplate = redisTemplate;
        this.tenantPlanCache = tenantPlanCache;
        this.metrics = metrics;
        this.quotaEnabled = quotaEnabled;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return new TenantContextGatewayFilter(configLoader, redisTemplate, tenantPlanCache, metrics, quotaEnabled);
    }

    /**
     * Main filter — runs at order +100, after JWT auth filters.
     *
     * <p>Resolves the canonical tenant ID, sets the header on the mutated request
     * (so downstream chain filters and the upstream destination can read it), and
     * stores it as an exchange attribute.
     */
    public static class TenantContextGatewayFilter implements GatewayFilter, Ordered {

        private static final String DEFAULT_TENANT_HEADER = RoutifyHeaders.TENANT_ID;

        private final GatewayConfigLoader configLoader;
        private final ReactiveStringRedisTemplate redisTemplate;
        private final GatewayTenantPlanCache tenantPlanCache;
        private final RoutifyMetrics metrics;
        private final boolean quotaEnabled;

        public TenantContextGatewayFilter(GatewayConfigLoader configLoader,
                                          ReactiveStringRedisTemplate redisTemplate,
                                          GatewayTenantPlanCache tenantPlanCache,
                                          RoutifyMetrics metrics,
                                          boolean quotaEnabled) {
            this.configLoader = configLoader;
            this.redisTemplate = redisTemplate;
            this.tenantPlanCache = tenantPlanCache;
            this.metrics = metrics;
            this.quotaEnabled = quotaEnabled;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

            TenantIsolationSnapshot isolation = resolveTenantIsolation();

            if (isolation.enabled()) {
                // ── Caller-provided mode ──────────────────────────────────────
                // The caller (or JWT filter) supplies X-Tenant-Id. Resolve and
                // cross-validate against the JWT claim when present.
                Mono<Void> mismatchResponse = checkCallerMismatch(exchange, isolation);
                if (mismatchResponse != null) {
                    return mismatchResponse;
                }
            }

            String canonicalTenantId;

            if (isolation.enabled()) {
                canonicalTenantId = resolveFromCaller(exchange, isolation);
                if (canonicalTenantId == null) {
                    log.debug("TenantContext: enabled=true but no tenant found — passing through");
                    return chain.filter(exchange);
                }
            } else {
                // ── Auto-inject mode ──────────────────────────────────────────
                // Callers are NOT expected to supply X-Tenant-Id. Resolve from
                // route metadata; honour any existing header/JWT value if present.
                canonicalTenantId = resolveAutoInject(exchange, isolation);
                if (canonicalTenantId == null) {
                    log.debug("TenantContext: no tenant info available — passing through");
                    return chain.filter(exchange);
                }
            }

            // Store as exchange attribute for other filters / future use
            exchange.getAttributes().put(ATTR_TENANT_ID, canonicalTenantId);

            String tenantHeader = isolation.tenantIdHeader();

            // Mutate request to include the header for downstream chain filters
            // and upstream destination (always forwarded)
            ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                    .header(tenantHeader, canonicalTenantId)
                    .build();
            ServerWebExchange mutatedExchange = exchange.mutate().request(mutatedRequest).build();

            log.debug("TenantContext: propagating tenant='{}' to chain and upstream via '{}'",
                    canonicalTenantId, tenantHeader);

            // ── Request quota enforcement ──────────────────────────────────
            if (quotaEnabled) {
                return enforceRequestQuota(canonicalTenantId, mutatedExchange, chain);
            }

            return chain.filter(mutatedExchange);
        }

        /**
         * Atomically increments the monthly request counter in Redis and checks
         * the tenant's plan quota. Returns HTTP 429 if the quota is exceeded.
         *
         * <p>Fully reactive — no blocking. Uses {@code ReactiveStringRedisTemplate}.
         */
        private Mono<Void> enforceRequestQuota(String tenantIdStr,
                                                ServerWebExchange exchange,
                                                GatewayFilterChain chain) {
            UUID tenantId;
            try {
                tenantId = UUID.fromString(tenantIdStr);
            } catch (IllegalArgumentException e) {
                // Non-UUID tenant IDs skip quota enforcement
                return chain.filter(exchange);
            }

            TenantPlan plan = tenantPlanCache.getPlan(tenantId);
            if (plan.monthlyRequestQuota() == Integer.MAX_VALUE) {
                // ENTERPRISE — unlimited
                return chain.filter(exchange);
            }

            YearMonth now = YearMonth.now(ZoneOffset.UTC);
            String redisKey = RedisKeys.QUOTA_PREFIX + tenantIdStr + ":" + now;

            return redisTemplate.opsForValue().increment(redisKey)
                    .flatMap(count -> {
                        if (count != null && count == 1L) {
                            // First request of the month — set TTL to end of month + 1 day buffer
                            var endOfMonth = now.atEndOfMonth().atStartOfDay().toInstant(ZoneOffset.UTC);
                            var ttl = Duration.between(java.time.Instant.now(), endOfMonth).plus(Duration.ofDays(1));
                            return redisTemplate.expire(redisKey, ttl).thenReturn(count);
                        }
                        return Mono.just(count);
                    })
                    .flatMap(count -> {
                        if (count > plan.monthlyRequestQuota()) {
                            metrics.recordQuotaExceeded();
                            return tooManyRequests(exchange, now);
                        }
                        return chain.filter(exchange);
                    })
                    .onErrorResume(e -> {
                        // Redis failure should not block requests — fail open
                        log.warn("Quota check Redis error — allowing request: {}", e.getMessage());
                        return chain.filter(exchange);
                    });
        }

        @Override
        public int getOrder() {
            // Must be AFTER JwtAuthGatewayFilterFactory writes X-Auth-Tenant-Id.
            return 100;
        }

        // ─── Resolution helpers ───────────────────────────────────────────────

        /**
         * Checks for tenant mismatch between JWT and caller-supplied header.
         *
         * @return a {@code Mono<Void>} that writes a 403 if mismatch is detected,
         *         or {@code null} if no mismatch
         */
        private Mono<Void> checkCallerMismatch(ServerWebExchange exchange,
                                                TenantIsolationSnapshot isolation) {
            ServerHttpRequest request = exchange.getRequest();
            String tenantHeader = isolation.tenantIdHeader();

            String authTenantId   = request.getHeaders().getFirst(RoutifyHeaders.AUTH_TENANT_ID);
            String callerTenantId = request.getHeaders().getFirst(tenantHeader);

            if (authTenantId != null && !authTenantId.isBlank()
                    && callerTenantId != null && !callerTenantId.isBlank()
                    && !authTenantId.equals(callerTenantId)) {
                log.warn("Tenant mismatch: JWT tenant='{}' != {}='{}' — rejecting request",
                        authTenantId, tenantHeader, callerTenantId);
                return forbidden(exchange,
                        "TENANT_MISMATCH",
                        "JWT tenant does not match the requested tenant");
            }
            return null;
        }

        /**
         * Caller-provided mode ({@code enabled=true}): resolves the canonical tenant
         * from caller headers / JWT. Assumes cross-validation has already been done.
         *
         * @return the canonical tenant ID, or {@code null} if none found
         */
        private String resolveFromCaller(ServerWebExchange exchange,
                                         TenantIsolationSnapshot isolation) {
            ServerHttpRequest request = exchange.getRequest();
            String tenantHeader = isolation.tenantIdHeader();

            String authTenantId  = request.getHeaders().getFirst(RoutifyHeaders.AUTH_TENANT_ID);
            String callerTenantId = request.getHeaders().getFirst(tenantHeader);

            if (authTenantId != null && !authTenantId.isBlank()) {
                log.debug("TenantContext (caller): resolved tenant='{}' from JWT", authTenantId);
                return authTenantId;
            }

            if (callerTenantId != null && !callerTenantId.isBlank()) {
                log.debug("TenantContext (caller): resolved tenant='{}' from header '{}'",
                        callerTenantId, tenantHeader);
                return callerTenantId;
            }

            return null;
        }

        /**
         * Auto-inject mode ({@code enabled=false}): resolves the tenant from the route
         * owner's metadata. If the caller or JWT already supplied a value, it is honoured.
         */
        private String resolveAutoInject(ServerWebExchange exchange,
                                         TenantIsolationSnapshot isolation) {
            ServerHttpRequest request = exchange.getRequest();
            String tenantHeader = isolation.tenantIdHeader();

            // Prefer explicit headers if already present (JWT or caller-supplied)
            String authTenantId   = request.getHeaders().getFirst(RoutifyHeaders.AUTH_TENANT_ID);
            String callerTenantId = request.getHeaders().getFirst(tenantHeader);

            if (authTenantId != null && !authTenantId.isBlank()) {
                log.debug("TenantContext (auto-inject): using JWT tenant='{}'", authTenantId);
                return authTenantId;
            }
            if (callerTenantId != null && !callerTenantId.isBlank()) {
                log.debug("TenantContext (auto-inject): using caller tenant='{}'", callerTenantId);
                return callerTenantId;
            }

            // Resolve from route metadata
            String routeOwnerTenantId = resolveRouteOwnerTenantId(exchange);
            if (routeOwnerTenantId != null) {
                log.debug("TenantContext (auto-inject): injecting route owner tenant='{}'",
                        routeOwnerTenantId);
                return routeOwnerTenantId;
            }

            return null;
        }

        /**
         * Reads the route's owning tenant ID from Spring Cloud Gateway route metadata.
         * The {@code tenantId} key is set by {@link RouteDefinitionBuilder#build}.
         */
        private String resolveRouteOwnerTenantId(ServerWebExchange exchange) {
            Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            if (route == null) return null;
            Object tenantId = route.getMetadata().get("tenantId");
            return (tenantId instanceof String s && !s.isBlank()) ? s : null;
        }

        /**
         * Reads the {@code tenantIsolation} section from the live gateway config.
         * Falls back to safe defaults (enabled, default header) if the config is
         * not yet loaded or the section is missing.
         */
        @SuppressWarnings("unchecked")
        private TenantIsolationSnapshot resolveTenantIsolation() {
            Map<String, Object> gwConfig = configLoader.getConfig();
            if (gwConfig == null || gwConfig.isEmpty()) return TenantIsolationSnapshot.DEFAULTS;
            Object raw = gwConfig.get("tenantIsolation");
            if (!(raw instanceof Map<?, ?> rawMap)) return TenantIsolationSnapshot.DEFAULTS;
            Map<String, Object> ti = (Map<String, Object>) rawMap;

            boolean enabled        = toBool(ti.get("enabled"), true);
            String  tenantIdHeader = ti.get("tenantIdHeader") instanceof String s && !s.isBlank()
                    ? s : DEFAULT_TENANT_HEADER;

            return new TenantIsolationSnapshot(enabled, tenantIdHeader);
        }

        private static boolean toBool(Object value, boolean defaultValue) {
            if (value == null)              return defaultValue;
            if (value instanceof Boolean b) return b;
            return Boolean.parseBoolean(value.toString().trim());
        }

        /** Immutable snapshot of tenant isolation settings for a single filter invocation. */
        private record TenantIsolationSnapshot(boolean enabled,
                                               String tenantIdHeader) {
            static final TenantIsolationSnapshot DEFAULTS =
                    new TenantIsolationSnapshot(true, DEFAULT_TENANT_HEADER);
        }
    }

    // ─── Shared helpers ───────────────────────────────────────────────────────

    private static Mono<Void> forbidden(ServerWebExchange exchange,
                                  String errorCode,
                                  String detail) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().set("Content-Type", "application/problem+json");
        String body = """
                {"type":"about:blank","title":"Forbidden","status":403,\
                "errorCode":"%s","detail":"%s"}""".formatted(errorCode, detail);
        var buffer = response.bufferFactory().wrap(body.getBytes());
        return response.writeWith(Mono.just(buffer));
    }

    private static Mono<Void> tooManyRequests(ServerWebExchange exchange, YearMonth currentMonth) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().set("Content-Type", "application/problem+json");
        // Calculate seconds until next month
        var nextMonth = currentMonth.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        long retryAfter = java.time.Instant.now().until(nextMonth, ChronoUnit.SECONDS);
        response.getHeaders().set("Retry-After", String.valueOf(retryAfter));
        String body = """
                {"type":"about:blank","title":"Too Many Requests","status":429,\
                "errorCode":"QUOTA_EXCEEDED","detail":"Monthly request quota exceeded. Retry after billing period reset."}""";
        var buffer = response.bufferFactory().wrap(body.getBytes());
        return response.writeWith(Mono.just(buffer));
    }

    public static class Config {
        // No configuration required.
    }
}

