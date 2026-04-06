package io.routify.admin.controller;

import io.routify.admin.client.IdentityMessagingClient;
import io.routify.common.event.QueryResponse;
import io.routify.common.exception.RoutifyException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Map;

/**
 * Auth controller — handles login / refresh / logout for the dashboard.
 *
 * <h2>Refresh token security model</h2>
 * <p>The refresh token is stored in an {@code HttpOnly; Secure; SameSite=Strict} cookie
 * named {@code refresh_token}.  On login the identity-service issues the token; the
 * admin-api proxies it to the browser via a {@code Set-Cookie} header.  On refresh the
 * browser sends the cookie automatically — the admin-api reads it, forwards it over
 * RabbitMQ to identity-service, receives a rotated token, and writes a new cookie.
 * JavaScript never touches the refresh token directly.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    static final String REFRESH_COOKIE_NAME = "refresh_token";

    /**
     * Set to {@code true} in production (HTTPS).  Defaults to {@code false} so that the
     * HttpOnly cookie is delivered over plain HTTP during local development.
     */
    @Value("${COOKIE_SECURE:false}")
    private boolean cookieSecure;

    private final IdentityMessagingClient messagingClient;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, Object> body,
                                   HttpServletResponse response) {
        String username   = str(body.get("username"));
        String password   = str(body.get("password"));
        String tenantSlug = str(body.get("tenantSlug"));

        QueryResponse.LoginResult result = messagingClient.login(username, password, tenantSlug);
        if (result == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "identity-service temporarily unavailable"));
        }

        // Deliver the refresh token as an HttpOnly cookie; strip it from the JSON body
        if (result.refreshToken() != null && !result.refreshToken().isBlank()) {
            writeRefreshCookie(result.refreshToken(), response);
        }
        // Return a copy without the refresh token
        return ResponseEntity.ok(withoutRefreshToken(result));
    }

    /**
     * Refresh endpoint — reads the {@code refresh_token} HttpOnly cookie sent automatically
     * by the browser.  No request body is required; the cookie value is forwarded to
     * identity-service via RabbitMQ RPC.  On success a rotated cookie is written.
     *
     * <p>If identity-service is unreachable (RabbitMQ timeout / circuit open) the method
     * returns {@code 503 Service Unavailable} and clears the refresh-token cookie so the
     * browser does not keep retrying with a stale token.  Any token-level rejection from
     * identity-service (expired / revoked token) is surfaced as {@code 401 Unauthorized}.
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest request,
                                     HttpServletResponse response) {
        String refreshToken = extractRefreshCookie(request);
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new RoutifyException.Unauthorized("Missing refresh token cookie");
        }

        try {
            QueryResponse.LoginResult result = messagingClient.refresh(refreshToken);
            if (result == null) {
                // Circuit breaker fallback returned null — identity-service is unavailable
                clearRefreshCookie(response);
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("error", "identity-service temporarily unavailable"));
            }

            // Token rotation
            if (result.refreshToken() != null && !result.refreshToken().isBlank()) {
                writeRefreshCookie(result.refreshToken(), response);
            }
            return ResponseEntity.ok(withoutRefreshToken(result));

        } catch (RoutifyException.GatewayError e) {
            log.warn("Token refresh failed — identity-service unreachable: {}", e.getMessage());
            clearRefreshCookie(response);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "identity-service temporarily unavailable"));
        } catch (RoutifyException.Unauthorized | RoutifyException.Forbidden e) {
            log.debug("Token refresh rejected by identity-service: {}", e.getMessage());
            clearRefreshCookie(response);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request,
                                       HttpServletResponse response) {
        String refreshToken = extractRefreshCookie(request);
        if (refreshToken != null && !refreshToken.isBlank()) {
            messagingClient.sendLogoutCommand(refreshToken);
        }
        clearRefreshCookie(response);
        return ResponseEntity.noContent().build();
    }

    /**
     * Self-service password change — any authenticated user can change their own password.
     *
     * <p>The caller must supply their current password for re-authentication.
     * On success the {@code mustChangePassword} flag is cleared on the account,
     * which unblocks users who were forced into the change-password wall on first login.
     */
    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(
            @RequestBody Map<String, Object> body,
            Authentication auth) {

        if (auth == null || auth.getName() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Not authenticated"));
        }

        String userIdStr       = str(body.get("userId"));
        String currentPassword = str(body.get("currentPassword"));
        String newPassword     = str(body.get("newPassword"));

        if (userIdStr.isBlank() || currentPassword.isBlank() || newPassword.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "userId, currentPassword and newPassword are required"));
        }

        try {
            java.util.UUID userId = java.util.UUID.fromString(userIdStr);
            QueryResponse.PasswordChangeResult result =
                    messagingClient.changePassword(userId, currentPassword, newPassword);
            if (result == null || !result.success()) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("error", "Password change failed"));
            }
            return ResponseEntity.ok(Map.of("success", true, "message", "Password changed successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid userId format"));
        }
    }

    // ─── Cookie helpers ───────────────────────────────────────────────────────

    private String extractRefreshCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies())
                .filter(c -> REFRESH_COOKIE_NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }

    private void writeRefreshCookie(String token, HttpServletResponse response) {
        int maxAge = 7 * 24 * 3600;
        response.addHeader("Set-Cookie", buildSetCookieHeader(token, maxAge));
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        response.addHeader("Set-Cookie", buildSetCookieHeader("", 0));
    }

    private String buildSetCookieHeader(String value, int maxAge) {
        String base = String.format(
                "%s=%s; Path=/api/v1/auth; Max-Age=%d; HttpOnly; SameSite=Lax",
                REFRESH_COOKIE_NAME, value, maxAge);
        return cookieSecure ? base + "; Secure" : base;
    }

    /**
     * Returns a view of the login result without the refresh token field
     * (it is delivered as an HttpOnly cookie instead).
     */
    private static QueryResponse.LoginResult withoutRefreshToken(QueryResponse.LoginResult r) {
        return new QueryResponse.LoginResult(
                r.accessToken(), null, r.tokenType(),
                r.expiresIn(), r.mustChangePassword(), r.user());
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static String str(Object val) {
        return val != null ? val.toString() : "";
    }
}
