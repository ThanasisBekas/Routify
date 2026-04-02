package gr.routify.route.config;

import gr.routify.common.web.RoutifyHeaders;
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
 * Pre-authentication filter for downstream services behind the API gateway.
 *
 * <p>The API gateway validates the JWT and injects trusted headers:
 * <ul>
 *   <li>{@code X-Auth-User-Id} — JWT subject (user UUID)</li>
 *   <li>{@code X-Auth-Tenant-Id} — tenant claim</li>
 *   <li>{@code X-Auth-Role} — role claim (e.g. SUPER_ADMIN)</li>
 *   <li>{@code X-Auth-Email} — email claim</li>
 * </ul>
 *
 * <p>This filter reads those headers and creates a Spring Security
 * {@link UsernamePasswordAuthenticationToken} so that Spring Security's
 * {@code .anyRequest().authenticated()} is satisfied.
 *
 * <p><strong>Security note:</strong> This is safe because in production these
 * services are not exposed directly; only the gateway can reach them.
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
            String role = request.getHeader(RoutifyHeaders.AUTH_ROLE);
            String tenantId = request.getHeader(RoutifyHeaders.AUTH_TENANT_ID);

            List<SimpleGrantedAuthority> authorities = (role != null && !role.isBlank())
                    ? List.of(new SimpleGrantedAuthority("ROLE_" + role))
                    : List.of();

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userId, null, authorities);

            // Store extra details (tenantId, etc.) for downstream access
            authentication.setDetails(new GatewayAuthDetails(userId, tenantId, role));

            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Holds additional gateway-forwarded auth details.
     */
    public record GatewayAuthDetails(String userId, String tenantId, String role) {}
}

