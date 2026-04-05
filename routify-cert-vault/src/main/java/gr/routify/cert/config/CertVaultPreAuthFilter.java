package gr.routify.cert.config;

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
 * Pre-authentication filter for routify-cert-vault behind the API gateway.
 * Reads gateway-forwarded auth headers to populate the Spring Security context.
 */
@Component
public class CertVaultPreAuthFilter extends OncePerRequestFilter {

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

            var auth = new UsernamePasswordAuthenticationToken(userId, null, authorities);
            auth.setDetails(new CertVaultAuthDetails(userId, tenantId, role));
            SecurityContextHolder.getContext().setAuthentication(auth);

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

    public record CertVaultAuthDetails(String userId, String tenantId, String role) {}
}

