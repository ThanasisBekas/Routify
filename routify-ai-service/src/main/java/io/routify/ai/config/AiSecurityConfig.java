package io.routify.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Security configuration for the AI service.
 *
 * <h3>Trust model</h3>
 * This service is a Kubernetes ClusterIP — it is not exposed outside the cluster.
 * Only the API Gateway service account can reach it (enforced by K8s NetworkPolicy).
 * The gateway injects pre-validated auth headers ({@code X-Auth-User-Id}, etc.) after
 * its own JWT validation, so we trust those headers here without re-validating the token.
 *
 * <p>This mirrors the {@code GatewayPreAuthFilter} pattern used in every other
 * internal Routify service (route-service, cert-vault, etc.).
 *
 * <h3>What is NOT protected at the HTTP layer</h3>
 * <ul>
 *   <li>CSRF — stateless REST API</li>
 *   <li>Session — stateless; no session created</li>
 *   <li>HTTP Basic / form login — not applicable</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class AiSecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   GatewayPreAuthFilter preAuthFilter) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                // Trust gateway pre-auth headers — network-layer security handles access control
                .addFilterBefore(preAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/api/v1/ai-filter/**").permitAll() // internal ClusterIP only
                        .anyRequest().permitAll()
                )
                .build();
    }
}

