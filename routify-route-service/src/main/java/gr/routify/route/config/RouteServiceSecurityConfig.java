package gr.routify.route.config;

import gr.routify.common.exception.GlobalExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Security config for routify-route-service.
 *
 * <p>routify-route-service is <strong>internal-only</strong>. It is NOT reachable
 * by the dashboard or external clients. All operations arrive via:
 * <ul>
 *   <li>Kafka — command events from routify-admin-api (route/filter writes)</li>
 *   <li>RabbitMQ — query requests from routify-admin-api and routify-api-gateway</li>
 * </ul>
 *
 * <p>The only HTTP surface remaining is {@code /api/v1/gateway-config} (internal
 * fallback) and {@code /actuator/health} for container health checks.
 * All other REST controllers have been removed — replaced by messaging handlers.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class RouteServiceSecurityConfig {

    private final GatewayPreAuthFilter gatewayPreAuthFilter;

    public RouteServiceSecurityConfig(GatewayPreAuthFilter gatewayPreAuthFilter) {
        this.gatewayPreAuthFilter = gatewayPreAuthFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(gatewayPreAuthFilter, UsernamePasswordAuthenticationFilter.class)
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



