package gr.routify.identity.controller;

import gr.routify.identity.dto.AuthDto;
import gr.routify.identity.security.JwtService;
import gr.routify.identity.service.AuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Authentication REST controller.
 * Public endpoints — no JWT required.
 *
 * <h2>Refresh token security model</h2>
 * <p>The refresh token is issued as an {@code HttpOnly; Secure; SameSite=Strict} cookie
 * named {@code refresh_token}.  It is <strong>never</strong> returned in the JSON response
 * body, which means JavaScript running in the browser cannot read it — eliminating the
 * primary XSS attack vector against session continuity.
 *
 * <p>The access token remains in Zustand memory (never persisted to localStorage).
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    /** Cookie name — must match the name the frontend expects. */
    static final String REFRESH_COOKIE_NAME = "refresh_token";

    private final AuthService authService;
    private final JwtService  jwtService;

    @PostMapping("/login")
    public ResponseEntity<AuthDto.LoginResponse> login(
            @Valid @RequestBody AuthDto.LoginRequest request,
            HttpServletResponse response) {

        AuthDto.LoginResponse result = authService.login(request);
        writeRefreshCookie(result.refreshToken(), response);
        return ResponseEntity.ok(result.withoutRefreshToken());
    }

    /**
     * Refresh endpoint — reads the refresh token from the {@code HttpOnly} cookie.
     * No request body is required; the browser sends the cookie automatically.
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthDto.LoginResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshTokenCookie,
            HttpServletResponse response) {

        if (refreshTokenCookie == null || refreshTokenCookie.isBlank()) {
            return ResponseEntity.status(401).build();
        }

        AuthDto.LoginResponse result = authService.refresh(
                new AuthDto.RefreshRequest(refreshTokenCookie));

        // Token rotation — overwrite the cookie with the newly issued refresh token
        writeRefreshCookie(result.refreshToken(), response);
        return ResponseEntity.ok(result.withoutRefreshToken());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshTokenCookie,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            HttpServletRequest request,
            HttpServletResponse response) {

        // Revoke the refresh token in Redis if present
        if (refreshTokenCookie != null && !refreshTokenCookie.isBlank()) {
            authService.revokeRefreshToken(refreshTokenCookie);
        }

        // Revoke the access token as well (short-circuit its remaining TTL)
        if (authorization != null && authorization.startsWith("Bearer ")) {
            authService.revokeAccessToken(authorization.substring(7));
        }

        // Clear the cookie by overwriting with an expired zero-value cookie
        clearRefreshCookie(response);
        return ResponseEntity.noContent().build();
    }

    // ─── Cookie helpers ───────────────────────────────────────────────────────

    private void writeRefreshCookie(String token, HttpServletResponse response) {
        Cookie cookie = new Cookie(REFRESH_COOKIE_NAME, token);
        cookie.setHttpOnly(true);
        // Secure=true is required for HTTPS deployments.
        // In local HTTP dev (localhost) this must be false so the browser sends the cookie.
        // Controlled by routify.jwt.refresh-cookie-secure (default false).
        cookie.setSecure(jwtService.isRefreshCookieSecure());
        // Scope the cookie to the refresh endpoint only — minimises CSRF surface
        cookie.setPath("/api/v1/auth/refresh");
        cookie.setMaxAge((int) jwtService.getRefreshTokenTtlSeconds());
        // SameSite=Strict prevents the cookie being sent on cross-site navigations
        response.addHeader("Set-Cookie",
                buildSetCookieHeader(token, cookie.getMaxAge()));
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        response.addHeader("Set-Cookie",
                buildSetCookieHeader("", 0));
    }

    /**
     * Builds a {@code Set-Cookie} header string with {@code SameSite=Strict}.
     * The Servlet Cookie API does not expose a SameSite setter, so we construct
     * the header manually as per RFC 6265 / modern browser requirements.
     * The {@code Secure} flag is included only when
     * {@code routify.jwt.refresh-cookie-secure=true} (production / HTTPS).
     */
    private String buildSetCookieHeader(String value, int maxAge) {
        String base = String.format(
                "%s=%s; Path=/api/v1/auth/refresh; Max-Age=%d; HttpOnly; SameSite=Strict",
                REFRESH_COOKIE_NAME, value, maxAge);
        return jwtService.isRefreshCookieSecure() ? base + "; Secure" : base;
    }
}
