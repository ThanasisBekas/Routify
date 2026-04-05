package gr.routify.ai.config;

import gr.routify.common.web.RoutifyHeaders;
import gr.routify.common.security.SecurityContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Pre-authentication filter — mirrors the pattern used in routify-route-service,
 * routify-cert-vault, and other internal Routify services.
 *
 * <p>Reads the trusted auth headers injected by the API Gateway's JWT filter and
 * creates a Spring Security authentication token, satisfying
 * {@code .anyRequest().authenticated()} if needed.
 *
 * <p>Trusted headers (set by the gateway after JWT validation):
 * <ul>
 *   <li>{@code X-Auth-User-Id}   — JWT subject</li>
 *   <li>{@code X-Auth-Tenant-Id} — tenant claim</li>
 *   <li>{@code X-Auth-Role}      — role claim</li>
 * </ul>
 */
@Component
public class GatewayPreAuthFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String userId = request.getHeader(RoutifyHeaders.AUTH_USER_ID);

        if (userId != null && !userId.isBlank()) {
            String role     = request.getHeader(RoutifyHeaders.AUTH_ROLE);
            String tenantId = request.getHeader(RoutifyHeaders.AUTH_TENANT_ID);

            List<SimpleGrantedAuthority> authorities = (role != null && !role.isBlank())
                    ? List.of(new SimpleGrantedAuthority("ROLE_" + role))
                    : List.of();

            var authentication = new UsernamePasswordAuthenticationToken(userId, null, authorities);
            authentication.setDetails(new GatewayAuthDetails(userId, tenantId, role));
            SecurityContextHolder.getContext().setAuthentication(authentication);

            // Enrich MDC for structured logging
            SecurityContext.putMdc(userId, tenantId,
                    request.getHeader(RoutifyHeaders.CORRELATION_ID));
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContext.clearMdc();
        }
    }

    /** Holds gateway-forwarded auth details accessible to downstream components. */
    public record GatewayAuthDetails(String userId, String tenantId, String role) {}
}

