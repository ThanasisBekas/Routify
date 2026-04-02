package gr.routify.admin.controller;

import gr.routify.admin.client.IdentityMessagingClient;
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
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, Object> body,
                                                     HttpServletResponse response) {
        String username   = str(body.get("username"));
        String password   = str(body.get("password"));
        String tenantSlug = str(body.get("tenantSlug"));

        Map<String, Object> result = messagingClient.login(username, password, tenantSlug);

        if (result.containsKey("error")) {
            return ResponseEntity.status(resolveErrorStatus(result)).body(result);
        }

        // Extract the refresh token from the RPC response and deliver it as an HttpOnly cookie.
        // Remove it from the JSON body so it is never exposed to JavaScript.
        String refreshToken = str(result.remove("refreshToken"));
        if (!refreshToken.isBlank()) {
            writeRefreshCookie(refreshToken, response);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Refresh endpoint — reads the {@code refresh_token} HttpOnly cookie sent automatically
     * by the browser.  No request body is required; the cookie value is forwarded to
     * identity-service via RabbitMQ RPC.  On success a rotated cookie is written.
     */
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh(HttpServletRequest request,
                                                       HttpServletResponse response) {
        String refreshToken = extractRefreshCookie(request);

        if (refreshToken == null || refreshToken.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", "Unauthorized", "error", "Missing refresh token cookie"));
        }

        Map<String, Object> result = messagingClient.refresh(refreshToken);

        if (result.containsKey("error")) {
            // Clear the stale cookie so the browser doesn't keep sending it
            clearRefreshCookie(response);
            return ResponseEntity.status(resolveErrorStatus(result)).body(result);
        }

        // Token rotation — write the new refresh token cookie and strip it from the JSON response
        String newRefreshToken = str(result.remove("refreshToken"));
        if (!newRefreshToken.isBlank()) {
            writeRefreshCookie(newRefreshToken, response);
        }

        return ResponseEntity.ok(result);
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
    public ResponseEntity<Map<String, Object>> changePassword(
            @RequestBody Map<String, Object> body,
            Authentication auth) {

        if (auth == null || auth.getName() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Not authenticated"));
        }

        String userIdStr      = str(body.get("userId"));
        String currentPassword = str(body.get("currentPassword"));
        String newPassword     = str(body.get("newPassword"));

        if (userIdStr.isBlank() || currentPassword.isBlank() || newPassword.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "userId, currentPassword and newPassword are required"));
        }

        try {
            java.util.UUID userId = java.util.UUID.fromString(userIdStr);
            Map<String, Object> result = messagingClient.changePassword(userId, currentPassword, newPassword);
            if (result.containsKey("error")) {
                return ResponseEntity.status(resolveErrorStatus(result)).body(result);
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
        // 7 days in seconds — matches identity-service default JWT_REFRESH_TTL
        int maxAge = 7 * 24 * 3600;
        response.addHeader("Set-Cookie", buildSetCookieHeader(token, maxAge));
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        response.addHeader("Set-Cookie", buildSetCookieHeader("", 0));
    }

    /**
     * Builds a {@code Set-Cookie} header with {@code SameSite=Lax}.
     * <ul>
     *   <li>{@code SameSite=Lax} — cookie is sent on same-site and top-level cross-site
     *       navigations, which covers the dev proxy and production gateway.
     *       {@code SameSite=Strict} was too restrictive: it silently drops the cookie on
     *       every cross-origin request, including the Vite dev-proxy scenario.</li>
     *   <li>{@code Secure} flag is controlled by the {@code COOKIE_SECURE} env var
     *       (default {@code false} for HTTP-based local development, {@code true} in prod).</li>
     *   <li>Path is widened to {@code /api/v1/auth} so the cookie is also forwarded on
     *       the {@code /logout} call.</li>
     * </ul>
     */
    private String buildSetCookieHeader(String value, int maxAge) {
        String base = String.format(
                "%s=%s; Path=/api/v1/auth; Max-Age=%d; HttpOnly; SameSite=Lax",
                REFRESH_COOKIE_NAME, value, maxAge);
        return cookieSecure ? base + "; Secure" : base;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static String str(Object val) {
        return val != null ? val.toString() : "";
    }

    private static HttpStatus resolveErrorStatus(Map<String, Object> result) {
        String code = result.getOrDefault("code", "").toString();
        return switch (code) {
            case "Unauthorized"      -> HttpStatus.UNAUTHORIZED;
            case "Forbidden"         -> HttpStatus.FORBIDDEN;
            case "NotFoundException" -> HttpStatus.NOT_FOUND;
            default                  -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }
}
