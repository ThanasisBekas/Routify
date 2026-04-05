package io.routify.gateway.filter;

import io.routify.gateway.config.GatewayConfigLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Gateway filter factory that injects OWASP-recommended security response headers
 * on every response it is applied to.
 *
 * <p>Header values are driven entirely by the persisted {@code securityHeaders} section
 * of the gateway config (saved via the dashboard → Gateway → Security Headers tab and
 * reloaded live from {@link GatewayConfigLoader}).  No gateway restart is required —
 * changes take effect on the next request after the Kafka {@code GatewayConfigChanged}
 * event propagates.
 *
 * <p>Filter type: {@code SECURITY_HEADERS}
 */
@Slf4j
@Component
public class SecurityHeadersGatewayFilterFactory
        extends AbstractGatewayFilterFactory<SecurityHeadersGatewayFilterFactory.Config> {

    private final GatewayConfigLoader configLoader;

    public SecurityHeadersGatewayFilterFactory(GatewayConfigLoader configLoader) {
        super(Config.class);
        this.configLoader = configLoader;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return new SecurityHeadersGatewayFilter(configLoader);
    }

    /**
     * Inner filter — implements {@link Ordered} and runs last (order 100) so all
     * upstream filters can still add headers before the security pass.
     *
     * <p>On every invocation the filter re-reads the live {@link GatewayConfigLoader}
     * snapshot so that admin changes (toggling individual headers, changing CSP, etc.)
     * are reflected immediately without a route reload.
     */
    public static class SecurityHeadersGatewayFilter implements GatewayFilter, Ordered {

        private final GatewayConfigLoader configLoader;

        public SecurityHeadersGatewayFilter(GatewayConfigLoader configLoader) {
            this.configLoader = configLoader;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            applySecurityHeaders(exchange.getResponse(), configLoader.getConfig());
            return chain.filter(exchange);
        }

        @Override
        public int getOrder() {
            return 100;
        }
    }

    // ─── Shared header-application logic (used by per-route filter AND global filter) ──

    /**
     * Applies security headers to the given response according to the {@code securityHeaders}
     * section of the provided gateway config map.
     *
     * <p>This is package-private so that {@code GlobalSecurityHeadersFilter} can reuse it
     * without duplicating the config-reading logic.
     *
     * @param response  the outbound HTTP response to mutate
     * @param gwConfig  the full gateway config map from {@link GatewayConfigLoader#getConfig()}
     */
    @SuppressWarnings("unchecked")
    static void applySecurityHeaders(ServerHttpResponse response, Map<String, Object> gwConfig) {
        var headers = response.getHeaders();

        // ── Resolve the securityHeaders config section ───────────────────────
        Object rawSection = gwConfig != null ? gwConfig.get("securityHeaders") : null;

        if (!(rawSection instanceof Map<?, ?> rawMap)) {
            // Config not loaded yet or malformed — apply safe OWASP defaults
            log.debug("SecurityHeaders: config section missing — applying hardcoded defaults");
            applyDefaults(headers);
            return;
        }

        Map<String, Object> sh = (Map<String, Object>) rawMap;

        // Master toggle
        if (!toBool(sh.get("enabled"), true)) {
            log.debug("SecurityHeaders: disabled in gateway config — no headers injected");
            return;
        }

        // ── Content & Frame Protection ───────────────────────────────────────
        if (toBool(sh.get("xContentTypeOptions"), true)) {
            headers.set("X-Content-Type-Options", "nosniff");
        }

        if (toBool(sh.get("xFrameOptions"), true)) {
            String frameValue = toStr(sh.get("xFrameOptionsValue"), "DENY");
            headers.set("X-Frame-Options", frameValue);
        }

        if (toBool(sh.get("xXssProtection"), true)) {
            headers.set("X-XSS-Protection", "1; mode=block");
        }

        // ── Transport Security (HSTS) ────────────────────────────────────────
        if (toBool(sh.get("strictTransportSecurity"), true)) {
            long maxAge = toLong(sh.get("stsMaxAge"), 31_536_000L);
            StringBuilder hsts = new StringBuilder("max-age=").append(maxAge);
            if (toBool(sh.get("stsIncludeSubDomains"), true))  hsts.append("; includeSubDomains");
            if (toBool(sh.get("stsPreload"),            false)) hsts.append("; preload");
            headers.set("Strict-Transport-Security", hsts.toString());
        }

        // ── Privacy & Permissions ────────────────────────────────────────────
        String referrerPolicy = toStr(sh.get("referrerPolicy"), "strict-origin-when-cross-origin");
        if (!referrerPolicy.isBlank()) {
            headers.set("Referrer-Policy", referrerPolicy);
        }

        String permissionsPolicy = toStr(sh.get("permissionsPolicy"), "geolocation=(), camera=(), microphone=()");
        if (!permissionsPolicy.isBlank()) {
            headers.set("Permissions-Policy", permissionsPolicy);
        }

        Object csp = sh.get("contentSecurityPolicy");
        if (csp instanceof String cspStr && !cspStr.isBlank()) {
            headers.set("Content-Security-Policy", cspStr);
        }

        // ── Custom headers ───────────────────────────────────────────────────
        Object customHeaders = sh.get("customHeaders");
        if (customHeaders instanceof Map<?, ?> custom) {
            custom.forEach((k, v) -> {
                if (k instanceof String key && v instanceof String val) {
                    headers.set(key, val);
                }
            });
        }

        // ── Server Fingerprinting ────────────────────────────────────────────
        if (toBool(sh.get("removeServerHeader"),     true)) headers.remove("Server");
        if (toBool(sh.get("removePoweredByHeader"),  true)) headers.remove("X-Powered-By");
    }

    /** Fallback: safe OWASP defaults when config is unavailable. */
    private static void applyDefaults(org.springframework.http.HttpHeaders headers) {
        headers.set("X-Content-Type-Options",      "nosniff");
        headers.set("X-Frame-Options",              "DENY");
        headers.set("X-XSS-Protection",             "1; mode=block");
        headers.set("Strict-Transport-Security",    "max-age=31536000; includeSubDomains");
        headers.set("Referrer-Policy",              "strict-origin-when-cross-origin");
        headers.set("Permissions-Policy",           "geolocation=(), camera=(), microphone=()");
        headers.remove("Server");
        headers.remove("X-Powered-By");
    }

    // ─── Config-reading helpers ──────────────────────────────────────────────

    private static boolean toBool(Object value, boolean defaultValue) {
        if (value == null)             return defaultValue;
        if (value instanceof Boolean b) return b;
        return Boolean.parseBoolean(value.toString().trim());
    }

    private static String toStr(Object value, String defaultValue) {
        if (value instanceof String s && !s.isBlank()) return s;
        return defaultValue;
    }

    private static long toLong(Object value, long defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.longValue();
        try { return Long.parseLong(value.toString().trim()); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    public static class Config {
        // No per-route configuration required — all settings come from the
        // persisted gateway config loaded by GatewayConfigLoader.
    }
}

