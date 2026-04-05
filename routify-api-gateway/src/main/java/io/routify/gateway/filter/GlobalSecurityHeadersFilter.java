package io.routify.gateway.filter;

import io.routify.gateway.config.GatewayConfigLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Global security-headers filter — applies OWASP-recommended response headers to
 * <em>every</em> response processed by the gateway, driven entirely by the persisted
 * {@code securityHeaders} section of the gateway config.
 *
 * <h3>Why a GlobalFilter in addition to the per-route SecurityHeaders filter?</h3>
 * <p>The per-route {@link SecurityHeadersGatewayFilterFactory} only activates on routes
 * that explicitly include a {@code SECURITY_HEADERS} filter entry.  This global filter
 * ensures headers are always present regardless of route configuration, honouring the
 * master {@code enabled} toggle and {@code globalFilters.securityHeaders.enabled} flag
 * from the dashboard Security Headers tab.
 *
 * <h3>Precedence</h3>
 * <ul>
 *   <li>This filter runs at order {@code Integer.MAX_VALUE - 1} (last-but-one),
 *       after all per-route filters have had a chance to add their own response headers.</li>
 *   <li>If a per-route {@code SECURITY_HEADERS} filter (order 100) has already set a header,
 *       this global filter will overwrite it with the config-driven value.  This is intentional
 *       — the global config is the source of truth.</li>
 * </ul>
 *
 * <h3>Hot-reload</h3>
 * <p>On every invocation the filter reads the live {@link GatewayConfigLoader} snapshot.
 * Admin saves from the dashboard propagate via Kafka {@code GatewayConfigChanged} events,
 * which trigger a config reload in the gateway.  Changes take effect immediately on the
 * next request — no gateway restart required.
 *
 * <h3>Disabling</h3>
 * <p>The filter is a no-op when either:
 * <ul>
 *   <li>{@code securityHeaders.enabled == false} (master toggle in the Security Headers tab), or</li>
 *   <li>{@code globalFilters.securityHeaders.enabled == false} (Global Filters tab toggle)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GlobalSecurityHeadersFilter implements GlobalFilter, Ordered {

    private final GatewayConfigLoader configLoader;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            Map<String, Object> gwConfig = configLoader.getConfig();

            // Respect the globalFilters.securityHeaders.enabled toggle
            if (!isGlobalFilterEnabled(gwConfig)) {
                log.debug("GlobalSecurityHeaders: disabled via globalFilters toggle — skipping");
                return;
            }

            // Delegate to the shared header-application logic in the factory
            SecurityHeadersGatewayFilterFactory.applySecurityHeaders(
                    exchange.getResponse(), gwConfig);
        }));
    }

    @Override
    public int getOrder() {
        // Run after all per-route filters (order 100) and after the response has been
        // committed to the chain, but before it is written to the wire.
        return Ordered.LOWEST_PRECEDENCE - 1;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Checks the {@code globalFilters.securityHeaders.enabled} flag.
     * Defaults to {@code true} (safe default) when config is absent or the section is missing.
     */
    @SuppressWarnings("unchecked")
    private static boolean isGlobalFilterEnabled(Map<String, Object> gwConfig) {
        if (gwConfig == null || gwConfig.isEmpty()) return true;

        Object rawGf = gwConfig.get("globalFilters");
        if (!(rawGf instanceof Map<?, ?> gfMap)) return true;

        Object rawSh = ((Map<String, Object>) gfMap).get("securityHeaders");
        if (!(rawSh instanceof Map<?, ?> shRef)) return true;

        Object enabled = ((Map<String, Object>) shRef).get("enabled");
        if (enabled == null) return true;
        if (enabled instanceof Boolean b) return b;
        return Boolean.parseBoolean(enabled.toString().trim());
    }
}

