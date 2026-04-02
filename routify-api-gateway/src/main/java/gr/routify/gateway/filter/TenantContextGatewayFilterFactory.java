package gr.routify.gateway.filter;

import gr.routify.common.web.RoutifyHeaders;
import gr.routify.gateway.config.GatewayConfigLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Gateway filter factory that propagates and validates tenant context through the request.
 *
 * <p>Runs <em>after</em> {@link JwtAuthGatewayFilterFactory} (order +100) so that
 * {@code X-Auth-Tenant-Id} — which is written by the JWT filter — is already present
 * on the request when this filter executes.
 *
 * <h3>Behaviour</h3>
 * <ol>
 *   <li>If tenant isolation is <strong>disabled</strong> in the gateway config, the filter
 *       is a no-op — it simply forwards the request unchanged.</li>
 *   <li>If a JWT was validated, {@code X-Auth-Tenant-Id} holds the authoritative tenant
 *       from the token claims. This value is used as the canonical tenant ID.</li>
 *   <li>If {@code X-Tenant-Id} was also supplied by the client (required by the route
 *       predicate), the two values are compared. A mismatch is rejected with
 *       {@code 403 Forbidden} to prevent a user from one tenant routing through
 *       another tenant's route.</li>
 *   <li>When no JWT auth filter is in the chain (e.g. API-key-only routes), the
 *       {@code X-Tenant-Id} from the already-validated route predicate is forwarded
 *       as-is.</li>
 * </ol>
 *
 * <p>Filter type: {@code TENANT_CONTEXT}
 */
@Slf4j
@Component
public class TenantContextGatewayFilterFactory
        extends AbstractGatewayFilterFactory<TenantContextGatewayFilterFactory.Config> {

    private final GatewayConfigLoader configLoader;

    public TenantContextGatewayFilterFactory(GatewayConfigLoader configLoader) {
        super(Config.class);
        this.configLoader = configLoader;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return new TenantContextGatewayFilter(configLoader);
    }

    /**
     * Inner filter — runs at order +100, after JWT auth filters (which run at their
     * default per-route order) and well after CorrelationId (-1000).
     *
     * <p>Previously this was at -900, which meant it executed <em>before</em> the JWT
     * filter had a chance to write {@code X-Auth-Tenant-Id}, so the header was always
     * null in the JWT-auth path.
     */
    public static class TenantContextGatewayFilter implements GatewayFilter, Ordered {

        private static final String DEFAULT_TENANT_HEADER = RoutifyHeaders.TENANT_ID;

        private final GatewayConfigLoader configLoader;

        public TenantContextGatewayFilter(GatewayConfigLoader configLoader) {
            this.configLoader = configLoader;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

            // ── Respect the tenant isolation enabled flag ──────────────────────
            // If isolation is disabled in gateway config, skip all tenant validation
            // and pass through without modification.
            if (!isTenantIsolationEnabled()) {
                log.debug("TenantContext: isolation disabled in gateway config — skipping");
                return chain.filter(exchange);
            }

            ServerHttpRequest request      = exchange.getRequest();
            String            tenantHeader = resolveTenantIdHeader();

            // Authoritative tenant set by JwtAuthGatewayFilterFactory after signature validation.
            String authTenantId  = request.getHeaders().getFirst(RoutifyHeaders.AUTH_TENANT_ID);
            // Tenant supplied by the caller and already matched by the route Header predicate.
            String routeTenantId = request.getHeaders().getFirst(tenantHeader);

            String canonicalTenantId;

            if (authTenantId != null && !authTenantId.isBlank()) {
                // JWT path — cross-validate against the route predicate header.
                // If both are present they MUST agree; otherwise the caller is trying to
                // use a JWT from tenant A to reach a route that belongs to tenant B.
                if (routeTenantId != null && !routeTenantId.isBlank()
                        && !authTenantId.equals(routeTenantId)) {
                    log.warn("Tenant mismatch: JWT tenant='{}' != {}='{}' — rejecting request",
                            authTenantId, tenantHeader, routeTenantId);
                    return forbidden(exchange,
                            "TENANT_MISMATCH",
                            "JWT tenant does not match the requested tenant");
                }
                canonicalTenantId = authTenantId;
            } else if (routeTenantId != null && !routeTenantId.isBlank()) {
                // Non-JWT auth path (e.g. API key) — the route predicate already validated
                // that X-Tenant-Id matches this route's tenant, so it is safe to forward.
                canonicalTenantId = routeTenantId;
            } else {
                // No tenant information at all — nothing to propagate.
                log.debug("TenantContext: no tenant ID found on request — skipping propagation");
                return chain.filter(exchange);
            }

            log.debug("TenantContext: propagating tenant='{}' downstream via header '{}'",
                    canonicalTenantId, tenantHeader);

            ServerHttpRequest mutatedRequest = request.mutate()
                    .header(tenantHeader, canonicalTenantId)
                    .build();
            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        }

        @Override
        public int getOrder() {
            // Must be AFTER JwtAuthGatewayFilterFactory writes X-Auth-Tenant-Id.
            return 100;
        }

        // ─── Helpers ──────────────────────────────────────────────────────────

        @SuppressWarnings("unchecked")
        private boolean isTenantIsolationEnabled() {
            Map<String, Object> gwConfig = configLoader.getConfig();
            if (gwConfig == null || gwConfig.isEmpty()) return true; // safe default
            Object raw = gwConfig.get("tenantIsolation");
            if (!(raw instanceof Map<?, ?> ti)) return true;
            Object enabled = ((Map<String, Object>) ti).get("enabled");
            if (enabled == null) return true;
            if (enabled instanceof Boolean b) return b;
            return Boolean.parseBoolean(enabled.toString().trim());
        }

        @SuppressWarnings("unchecked")
        private String resolveTenantIdHeader() {
            Map<String, Object> gwConfig = configLoader.getConfig();
            if (gwConfig == null || gwConfig.isEmpty()) return DEFAULT_TENANT_HEADER;
            Object raw = gwConfig.get("tenantIsolation");
            if (!(raw instanceof Map<?, ?> ti)) return DEFAULT_TENANT_HEADER;
            Object header = ((Map<String, Object>) ti).get("tenantIdHeader");
            return (header instanceof String s && !s.isBlank()) ? s : DEFAULT_TENANT_HEADER;
        }
    }

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

    public static class Config {
        // No configuration required.
    }
}

