package io.routify.gateway.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.reactive.CorsConfigurationSource;

/**
 * Gateway security configuration.
 *
 * <p>Spring Security is intentionally kept minimal — actual authentication and
 * authorization are handled by our custom gateway filter factories (JwtAuth, ApiKeyAuth, etc.)
 * at the route level, giving fine-grained per-route control.
 *
 * <p>This config:
 * <ul>
 *   <li>Disables CSRF (stateless API gateway)</li>
 *   <li>Configures CORS via {@link DynamicCorsConfigurationSource} so that Spring Security
 *       permits preflight/cross-origin requests using the live config loaded from the DB
 *       by {@link GatewayConfigLoader}.  Changes saved through the dashboard take effect
 *       immediately — no gateway restart required.</li>
 *   <li>Permits all at Spring Security level (filters handle auth)</li>
 * </ul>
 */
@Configuration
@EnableWebFluxSecurity
@RequiredArgsConstructor
public class GatewaySecurityConfig {

    private final GatewayConfigLoader configLoader;

    /** Fallback origins used when the DB config is absent or disabled. */
    @Value("${CORS_ALLOWED_ORIGINS:http://localhost:5173}")
    private String fallbackOrigins;

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/fallback/**").permitAll()
                        .anyExchange().permitAll()  // Route-level auth via gateway filters
                )
                .build();
    }

    /**
     * Returns a {@link DynamicCorsConfigurationSource} that reads the live CORS
     * configuration from {@link GatewayConfigLoader} on every request.
     *
     * <p>Because the source delegates to the loader's {@code AtomicReference} snapshot,
     * any admin change propagated via the Kafka {@code GatewayConfigChanged} event is
     * reflected immediately — without restarting or re-creating the Spring Security filter chain.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        return new DynamicCorsConfigurationSource(configLoader, fallbackOrigins);
    }
}
