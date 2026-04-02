package gr.routify.admin.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Security configuration for routify-admin-api.
 *
 * <h2>Defence-in-depth model</h2>
 * <p>Two independent layers protect every admin endpoint:
 * <ol>
 *   <li><b>Spring Security authorisation</b> — {@code .authenticated()} requires a valid
 *       {@link org.springframework.security.core.Authentication} in the
 *       {@link org.springframework.security.core.context.SecurityContext} before the
 *       request reaches any controller method.</li>
 *   <li><b>{@link JwtAuthFilter}</b> — validates the RS256 JWT, parses claims, and
 *       populates that {@code SecurityContext}.  Registered explicitly via
 *       {@link HttpSecurity#addFilterBefore} so it runs exactly once per request as
 *       part of the security filter chain (not as a servlet filter).</li>
 * </ol>
 *
 * <p>Consequence: even if {@code JwtAuthFilter} were misconfigured or skipped for a
 * new endpoint, Spring Security would still block unauthenticated access with HTTP 401.
 *
 * <p>routify-admin-api is the <strong>sole backend</strong> for the Routify dashboard.
 * The API gateway routes all {@code /api/v1/admin/**} and {@code /ws/**} requests here
 * after first-layer JWT validation. All other microservices are NOT reachable externally.
 *
 * <p>In production, harden further by requiring an internal service header
 * (e.g. {@code X-Internal-Auth}) or mTLS between the gateway and admin-api.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(securedEnabled = true)
public class AdminSecurityConfig {

    @Autowired
    private CorsConfigurationSource corsConfigurationSource;

    @Bean
    public JwtAuthFilter jwtAuthFilter() {
        return new JwtAuthFilter();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthFilter jwtAuthFilter) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // Register JwtAuthFilter explicitly in the security chain — runs before
            // UsernamePasswordAuthenticationFilter.  Populates SecurityContext so that
            // the .authenticated() rules below can evaluate the principal.
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)

            .authorizeHttpRequests(authz -> authz
                // ── Public endpoints ──────────────────────────────────────────
                // Auth endpoints — login / refresh / logout require no token.
                .requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh",
                                 "/api/v1/auth/logout").permitAll()
                // Workspaces list — public for login-page dropdown (name+slug only)
                .requestMatchers("/api/v1/admin/tenants/workspaces").permitAll()
                // Actuator health/info — accessible to load-balancer probes without auth.
                .requestMatchers("/actuator/health", "/actuator/health/**",
                                 "/actuator/info").permitAll()

                // ── Protected endpoints ───────────────────────────────────────
                // Self-service password change — requires a valid JWT.
                .requestMatchers("/api/v1/auth/change-password").authenticated()
                // All admin REST endpoints require a valid, non-expired JWT.
                .requestMatchers("/api/v1/admin/**").authenticated()
                // WebSocket / SockJS handshake endpoints must be permitted at the HTTP layer
                // so the browser can complete the TCP upgrade without a pre-existing cookie/session.
                // Authentication is enforced inside the STOMP layer: JwtAuthFilter reads the
                // Bearer token from the ?token= query parameter on the upgrade request, and the
                // ChannelInterceptor (if added) can reject unauthenticated CONNECT frames.
                .requestMatchers("/ws/**").permitAll()

                // Catch-all: anything not matched above requires authentication.
                .anyRequest().authenticated()
            );

        return http.build();
    }
}

