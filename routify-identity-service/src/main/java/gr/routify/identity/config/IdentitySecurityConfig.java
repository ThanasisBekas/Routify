package gr.routify.identity.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security config for routify-identity-service.
 *
 * <p>identity-service exposes a minimal HTTP surface:
 * <ul>
 *   <li>{@code /api/v1/auth/**} — public JWT issuance (login, refresh, logout).
 *       These are reachable via the API gateway by external clients.</li>
 *   <li>{@code /actuator/health} — container health check.</li>
 * </ul>
 *
 * <p>All other operations (user CRUD, tenant management) are handled exclusively
 * via messaging — RabbitMQ queries and Kafka commands from routify-admin-api.
 * The {@code UserController} and {@code TenantController} REST controllers have
 * been removed; admin-api is the sole entry point for dashboard operations.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class IdentitySecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Auth endpoints are public — JWT issuance for external clients
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        // Health/info for container orchestration
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        // All other HTTP requests are denied — use messaging instead
                        .anyRequest().denyAll()
                )
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}

