package io.routify.cert.config;

import io.routify.common.exception.GlobalExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Security configuration for routify-cert-vault.
 *
 * <p>cert-vault is <strong>internal-only</strong>. Not reachable by external clients.
 * All operations arrive via Kafka (commands) and RabbitMQ (queries).
 * The HTTP surface is limited to {@code /actuator/health} for container health checks.
 */
@Configuration
@EnableWebSecurity
public class CertVaultSecurityConfig {

    private final CertVaultPreAuthFilter preAuthFilter;

    public CertVaultSecurityConfig(CertVaultPreAuthFilter preAuthFilter) {
        this.preAuthFilter = preAuthFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(preAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .anyRequest().authenticated()
                )
                .build();
    }

    @Bean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }
}

