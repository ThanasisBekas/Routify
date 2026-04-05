package gr.routify.admin.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.SignatureException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import gr.routify.common.web.RoutifyHeaders;
import gr.routify.common.security.SecurityContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/**
 * JWT authentication filter for routify-admin-api.
 *
 * <h2>Registration</h2>
 * <p>This filter is registered explicitly via
 * {@link org.springframework.security.config.annotation.web.builders.HttpSecurity#addFilterBefore}
 * in {@link AdminSecurityConfig}, positioned before
 * {@link org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter}.
 * It is <strong>not</strong> annotated with {@code @Component} to prevent Spring Boot
 * from also registering it as a standalone servlet filter — doing so would cause it to
 * execute twice per request (once outside the security chain, once inside).
 *
 * <h2>Behaviour</h2>
 * <ul>
 *   <li>Extracts the Bearer token from the {@code Authorization} header, or from the
 *       {@code ?token=} query parameter for WebSocket/SockJS handshakes.</li>
 *   <li>Validates the RS256 signature against the configured RSA public key.</li>
 *   <li>Populates the {@link org.springframework.security.core.context.SecurityContext}
 *       with a {@link UsernamePasswordAuthenticationToken} containing the user principal
 *       and {@code ROLE_<role>} authority.</li>
 *   <li>Wraps the request to inject {@code X-Auth-*} headers for downstream services.</li>
 * </ul>
 *
 * <p>In dev mode (no public key configured), signature verification is skipped —
 * matching the gateway's dev-mode behaviour.  This must never happen in production.
 */
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

    @Value("${routify.jwt.public-key:}")
    private String publicKeyBase64;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        // JWT parsing only — keep filterChain.doFilter() OUTSIDE this try/catch so that
        // exceptions thrown by downstream handlers (e.g. Jackson deserialization errors)
        // are never mistakenly caught here and do not cause a double-write on the response.
        HttpServletRequest requestToChain;
        try {
            Claims claims = parseAndValidate(token);

            // Set Spring Security authentication
            String role = getClaimOrEmpty(claims, "role");
            var authorities = role.isEmpty()
                    ? Collections.<SimpleGrantedAuthority>emptyList()
                    : List.of(new SimpleGrantedAuthority("ROLE_" + role));
            var auth = new UsernamePasswordAuthenticationToken(
                    claims.getSubject(), null, authorities);
            auth.setDetails(claims);
            SecurityContextHolder.getContext().setAuthentication(auth);

            // Wrap request to inject X-Auth-* headers (used by proxy to downstream services)
            requestToChain = new AuthHeadersRequestWrapper(request, claims);

            // Enrich MDC for structured logging (tenantId, userId, correlationId)
            SecurityContext.putMdc(
                    claims.getSubject(),
                    getClaimOrEmpty(claims, "tenantId"),
                    request.getHeader(RoutifyHeaders.CORRELATION_ID));

        } catch (ExpiredJwtException e) {
            sendUnauthorized(response, "TOKEN_EXPIRED", "JWT token has expired");
            return;
        } catch (SignatureException | MalformedJwtException | UnsupportedJwtException e) {
            sendUnauthorized(response, "INVALID_TOKEN", "JWT token is invalid");
            return;
        } catch (Exception e) {
            log.error("JWT validation error: {}", e.getMessage());
            sendUnauthorized(response, "TOKEN_VALIDATION_FAILED", "Token validation failed");
            return;
        }

        // Proceed with the filter chain — any exceptions here belong to the dispatcher,
        // not to JWT validation, and must propagate normally.
        try {
            filterChain.doFilter(requestToChain, response);
        } finally {
            SecurityContext.clearMdc();
        }
    }

    private String extractToken(HttpServletRequest request) {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        // WebSocket/SSE fallback
        String queryToken = request.getParameter("token");
        if (queryToken != null && !queryToken.isBlank()) {
            return queryToken;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Claims parseAndValidate(String token) throws Exception {
        if (publicKeyBase64 != null && !publicKeyBase64.isBlank()) {
            byte[] keyBytes = Base64.getDecoder().decode(publicKeyBase64);
            PublicKey publicKey = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(keyBytes));
            return Jwts.parser().verifyWith(publicKey).build()
                    .parseSignedClaims(token).getPayload();
        } else {
            // Dev mode: decode claims without signature verification
            log.warn("JWT public key not configured — signature SKIPPED (dev mode only!)");
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                throw new MalformedJwtException("JWT must have at least 2 parts");
            }
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]),
                    java.nio.charset.StandardCharsets.UTF_8);
            Map<String, Object> claimsMap = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(payload, Map.class);

            // Check expiration manually
            Object exp = claimsMap.get("exp");
            if (exp instanceof Number n) {
                if (java.time.Instant.ofEpochSecond(n.longValue()).isBefore(java.time.Instant.now())) {
                    throw new ExpiredJwtException(null, null, "JWT token has expired");
                }
            }
            return Jwts.claims().add(claimsMap).build();
        }
    }

    private String getClaimOrEmpty(Claims claims, String key) {
        Object val = claims.get(key);
        return val != null ? val.toString() : "";
    }

    private void sendUnauthorized(HttpServletResponse response, String errorCode, String detail) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/problem+json");
        response.getWriter().write("""
                {"type":"about:blank","title":"Unauthorized","status":401,\
                "errorCode":"%s","detail":"%s"}\
                """.formatted(errorCode, detail));
    }

    /**
     * Wraps the original request to inject X-Auth-* headers extracted from JWT claims.
     * These headers are what downstream services (route-service, identity-service, etc.) expect.
     *
     * <p>X-Auth-Tenant-Id is ALWAYS set from the JWT (authoritative, used for security
     * cross-validation by the gateway's TenantContextFilter).
     *
     * <p>X-Tenant-Id is only injected from the JWT when the caller has NOT already supplied
     * it explicitly. This allows SUPER_ADMIN users to pass a different X-Tenant-Id to target
     * a specific workspace (e.g. creating a user in another tenant) without having their
     * header overwritten by their own JWT tenantId claim.
     */
    private static class AuthHeadersRequestWrapper extends HttpServletRequestWrapper {
        private final Map<String, String> extraHeaders = new HashMap<>();

        AuthHeadersRequestWrapper(HttpServletRequest request, Claims claims) {
            super(request);
            extraHeaders.put(RoutifyHeaders.AUTH_USER_ID,  claims.getSubject() != null ? claims.getSubject() : "");
            extraHeaders.put(RoutifyHeaders.AUTH_TENANT_ID, getVal(claims, "tenantId"));
            extraHeaders.put(RoutifyHeaders.AUTH_ROLE,      getVal(claims, "role"));
            extraHeaders.put(RoutifyHeaders.AUTH_EMAIL,     getVal(claims, "email"));
            // Only set X-Tenant-Id from JWT if the client did NOT send an explicit value.
            // This preserves SUPER_ADMIN cross-workspace operations where the dashboard
            // deliberately sends a different X-Tenant-Id to target another workspace.
            String clientTenantId = request.getHeader(RoutifyHeaders.TENANT_ID);
            if (clientTenantId == null || clientTenantId.isBlank()) {
                extraHeaders.put(RoutifyHeaders.TENANT_ID, getVal(claims, "tenantId"));
            }
        }

        private static String getVal(Claims claims, String key) {
            Object v = claims.get(key);
            return v != null ? v.toString() : "";
        }

        @Override
        public String getHeader(String name) {
            String extra = extraHeaders.get(name);
            return extra != null ? extra : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Set<String> names = new LinkedHashSet<>();
            Enumeration<String> original = super.getHeaderNames();
            while (original.hasMoreElements()) {
                names.add(original.nextElement());
            }
            names.addAll(extraHeaders.keySet());
            return Collections.enumeration(names);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            String extra = extraHeaders.get(name);
            if (extra != null) {
                return Collections.enumeration(List.of(extra));
            }
            return super.getHeaders(name);
        }
    }
}

