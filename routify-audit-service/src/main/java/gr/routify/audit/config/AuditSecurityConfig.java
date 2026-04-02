package gr.routify.audit.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security config for routify-audit-service.
 *
 * <p>audit-service is <strong>internal-only</strong>. All query and write operations
 * arrive via messaging:
 * <ul>
 *   <li>Kafka — domain events and request telemetry ingested from the gateway</li>
 *   <li>RabbitMQ — query requests from routify-admin-api (audit events, request logs)</li>
 * </ul>
 *
 * <p>All REST controllers have been removed. The only HTTP surface is
 * {@code /actuator/health} and {@code /actuator/info} for container health checks.
 * All other HTTP requests are denied.
 */
@Configuration
@EnableWebSecurity
public class AuditSecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .anyRequest().denyAll()
                )
                .build();
    }
}

