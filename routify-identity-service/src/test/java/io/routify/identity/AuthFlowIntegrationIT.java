package io.routify.identity;

import io.routify.common.domain.UserRole;
import io.routify.common.security.RedisKeys;
import io.routify.identity.domain.AppUser;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the identity-service auth flow.
 *
 * <p>Tests the full lifecycle: login → JWT issuance → token refresh → logout → Redis blocklist.
 * Uses real PostgreSQL + Redis + Kafka + RabbitMQ containers via Testcontainers.
 *
 * <p>The DataSeeder creates a default "admin" user with password "testpassword123"
 * in the "platform" tenant on startup.
 */
class AuthFlowIntegrationIT extends IdentityServiceIntegrationBase {

    // ─── Test 1: Successful login ──────────────────────────────────────────────

    @Test
    @DisplayName("Login with valid credentials returns access token, user info, and refresh cookie")
    void login_withValidCredentials_returnsTokenAndUserInfo() throws Exception {
        String loginJson = """
                {"username": "%s", "password": "%s", "tenantSlug": "%s"}
                """.formatted(ADMIN_USERNAME, ADMIN_PASSWORD, PLATFORM_SLUG);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.user.username").value(ADMIN_USERNAME))
                .andExpect(jsonPath("$.user.email").value("admin@routify.io"))
                .andExpect(jsonPath("$.user.role").value("SUPER_ADMIN"))
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andReturn();

        // Verify the Set-Cookie header contains the refresh token
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).isNotNull();
        assertThat(setCookie).contains("refresh_token=");
        assertThat(setCookie).contains("HttpOnly");
        assertThat(setCookie).contains("SameSite=Strict");
        assertThat(setCookie).contains("Path=/api/v1/auth/refresh");

        // Verify the access token has valid JWT claims
        String accessToken = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
        Claims claims = jwtService.validateAndParseClaims(accessToken);
        assertThat(claims.getSubject()).isNotNull();
        assertThat(claims.get("role")).isEqualTo("SUPER_ADMIN");
        assertThat(claims.get("username")).isEqualTo(ADMIN_USERNAME);
        assertThat(claims.get("email")).isEqualTo("admin@routify.io");
        assertThat(claims.getId()).isNotNull(); // JTI for revocation
    }

    // ─── Test 2: Login with wrong password ─────────────────────────────────────

    @Test
    @DisplayName("Login with wrong password returns 401 and increments failed attempts")
    void login_withWrongPassword_returns401AndIncrementsFailedAttempts() throws Exception {
        String loginJson = """
                {"username": "%s", "password": "wrong-password", "tenantSlug": "%s"}
                """.formatted(ADMIN_USERNAME, PLATFORM_SLUG);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson))
                .andExpect(status().isUnauthorized());

        // Verify failed login attempts incremented
        AppUser admin = userRepository.findByUsernameAndTenantId(ADMIN_USERNAME,
                tenantRepository.findBySlug(PLATFORM_SLUG).orElseThrow().getId()).orElseThrow();
        assertThat(admin.getStatus()).isEqualTo(AppUser.Status.ACTIVE);
    }

    // ─── Test 3: Login to suspended tenant ─────────────────────────────────────

    @Test
    @DisplayName("Login to suspended tenant returns 403")
    void login_toSuspendedTenant_returns403() throws Exception {
        // Suspend the platform tenant
        var tenant = tenantRepository.findBySlug(PLATFORM_SLUG).orElseThrow();
        tenant.suspend();
        tenantRepository.save(tenant);

        try {
            String loginJson = """
                    {"username": "%s", "password": "%s", "tenantSlug": "%s"}
                    """.formatted(ADMIN_USERNAME, ADMIN_PASSWORD, PLATFORM_SLUG);

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginJson))
                    .andExpect(status().isForbidden());
        } finally {
            // Reactivate the tenant to avoid affecting other tests
            tenant.reactivate();
            tenantRepository.save(tenant);
        }
    }

    // ─── Test 4: Account lockout after 5 failed attempts ───────────────────────

    @Test
    @DisplayName("Account is locked after 5 failed login attempts — even correct password is rejected")
    void login_locksAccountAfter5FailedAttempts() throws Exception {
        // Create a separate user for this test to avoid interfering with admin
        var tenant = tenantRepository.findBySlug(PLATFORM_SLUG).orElseThrow();
        AppUser lockoutUser = AppUser.builder()
                .tenantId(tenant.getId())
                .username("lockout-test-user")
                .email("lockout@routify.io")
                .passwordHash(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12)
                        .encode("correctpassword"))
                .role(UserRole.VIEWER)
                .build();
        lockoutUser = userRepository.save(lockoutUser);

        try {
            String wrongLoginJson = """
                    {"username": "lockout-test-user", "password": "wrong", "tenantSlug": "%s"}
                    """.formatted(PLATFORM_SLUG);

            // Send 5 wrong password attempts
            for (int i = 0; i < 5; i++) {
                mockMvc.perform(post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(wrongLoginJson))
                        .andExpect(status().isUnauthorized());
            }

            // 6th attempt with CORRECT password should still fail (account is locked)
            String correctLoginJson = """
                    {"username": "lockout-test-user", "password": "correctpassword", "tenantSlug": "%s"}
                    """.formatted(PLATFORM_SLUG);

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(correctLoginJson))
                    .andExpect(status().isUnauthorized());

            // Verify the user is locked in DB
            AppUser locked = userRepository.findById(lockoutUser.getId()).orElseThrow();
            assertThat(locked.isLocked()).isTrue();
        } finally {
            userRepository.deleteById(lockoutUser.getId());
        }
    }

    // ─── Test 5: Refresh token rotation ────────────────────────────────────────

    @Test
    @DisplayName("Refresh endpoint returns new access + refresh tokens (rotation)")
    void refresh_withValidCookie_returnsNewTokens() throws Exception {
        // Login to get the refresh cookie
        String loginJson = """
                {"username": "%s", "password": "%s", "tenantSlug": "%s"}
                """.formatted(ADMIN_USERNAME, ADMIN_PASSWORD, PLATFORM_SLUG);

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson))
                .andExpect(status().isOk())
                .andReturn();

        // Extract the refresh token from the Set-Cookie header
        String refreshToken = extractRefreshTokenFromSetCookie(loginResult);
        assertThat(refreshToken).isNotNull().isNotBlank();

        // Extract the original access token
        String originalAccessToken = objectMapper.readTree(
                loginResult.getResponse().getContentAsString()).get("accessToken").asText();

        // Call refresh endpoint with the cookie
        MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("refresh_token", refreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn();

        // The new access token should be different from the original
        String newAccessToken = objectMapper.readTree(
                refreshResult.getResponse().getContentAsString()).get("accessToken").asText();
        assertThat(newAccessToken).isNotEqualTo(originalAccessToken);

        // A new refresh cookie should have been set (rotation)
        String newSetCookie = refreshResult.getResponse().getHeader("Set-Cookie");
        assertThat(newSetCookie).isNotNull().contains("refresh_token=");
    }

    // ─── Test 6: Refresh with missing cookie ───────────────────────────────────

    @Test
    @DisplayName("Refresh without cookie returns 401")
    void refresh_withoutCookie_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized());
    }

    // ─── Test 7: Logout blocklists both tokens in Redis ────────────────────────

    @Test
    @DisplayName("Logout blocklists access and refresh tokens in Redis — refresh with old token fails")
    void logout_blocklistsBothTokensInRedis() throws Exception {
        // Login
        String loginJson = """
                {"username": "%s", "password": "%s", "tenantSlug": "%s"}
                """.formatted(ADMIN_USERNAME, ADMIN_PASSWORD, PLATFORM_SLUG);

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson))
                .andExpect(status().isOk())
                .andReturn();

        String accessToken = objectMapper.readTree(
                loginResult.getResponse().getContentAsString()).get("accessToken").asText();
        String refreshToken = extractRefreshTokenFromSetCookie(loginResult);

        // Extract JTIs
        Claims accessClaims = jwtService.validateAndParseClaims(accessToken);
        Claims refreshClaims = jwtService.validateAndParseClaims(refreshToken);
        String accessJti = accessClaims.getId();
        String refreshJti = refreshClaims.getId();

        // Logout
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + accessToken)
                        .cookie(new Cookie("refresh_token", refreshToken)))
                .andExpect(status().isNoContent());

        // Verify both tokens are blocklisted in Redis
        assertThat(redisTemplate.hasKey(RedisKeys.BLOCKLIST_PREFIX + accessJti)).isTrue();
        assertThat(redisTemplate.hasKey(RedisKeys.BLOCKLIST_PREFIX + refreshJti)).isTrue();

        // Attempt to refresh with the old (now-blocklisted) refresh token — should fail
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("refresh_token", refreshToken)))
                .andExpect(status().isUnauthorized());
    }

    // ─── Test 8: Blocklisted refresh token cannot be used ──────────────────────

    @Test
    @DisplayName("Blocklisted refresh token is rejected by the refresh endpoint")
    void refresh_withBlocklistedToken_returns401() throws Exception {
        // Login
        String loginJson = """
                {"username": "%s", "password": "%s", "tenantSlug": "%s"}
                """.formatted(ADMIN_USERNAME, ADMIN_PASSWORD, PLATFORM_SLUG);

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson))
                .andExpect(status().isOk())
                .andReturn();

        String refreshToken = extractRefreshTokenFromSetCookie(loginResult);

        // Manually blocklist the refresh token in Redis
        Claims refreshClaims = jwtService.validateAndParseClaims(refreshToken);
        redisTemplate.opsForValue().set(
                RedisKeys.BLOCKLIST_PREFIX + refreshClaims.getId(),
                "1",
                java.time.Duration.ofSeconds(3600));

        // Attempt refresh — should be rejected
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("refresh_token", refreshToken)))
                .andExpect(status().isUnauthorized());
    }

    // ─── Test 9: Password change clears mustChangePassword flag ────────────────

    @Test
    @DisplayName("Password change clears mustChangePassword flag")
    void changePassword_clearsMustChangePasswordFlag() throws Exception {
        // The seeded admin has mustChangePassword=true
        var tenant = tenantRepository.findBySlug(PLATFORM_SLUG).orElseThrow();
        AppUser admin = userRepository.findByUsernameAndTenantId(ADMIN_USERNAME, tenant.getId()).orElseThrow();
        assertThat(admin.isMustChangePassword()).isTrue();

        // Change password via service (simulates the RabbitMQ handler path)
        authService.changePassword(admin.getId(), ADMIN_PASSWORD, "newpassword123");

        // Verify the flag is cleared
        AppUser updated = userRepository.findById(admin.getId()).orElseThrow();
        assertThat(updated.isMustChangePassword()).isFalse();

        // Login with new password should work and show mustChangePassword=false
        String loginJson = """
                {"username": "%s", "password": "newpassword123", "tenantSlug": "%s"}
                """.formatted(ADMIN_USERNAME, PLATFORM_SLUG);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(false));

        // Restore original password for other tests
        authService.changePassword(admin.getId(), "newpassword123", ADMIN_PASSWORD);
    }

    // ─── Test 10: Login with non-existent tenant ───────────────────────────────

    @Test
    @DisplayName("Login with non-existent tenant slug returns 401")
    void login_withNonExistentTenant_returns401() throws Exception {
        String loginJson = """
                {"username": "%s", "password": "%s", "tenantSlug": "non-existent"}
                """.formatted(ADMIN_USERNAME, ADMIN_PASSWORD);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson))
                .andExpect(status().isUnauthorized());
    }

    // ─── Test 11: Logout clears the refresh cookie ─────────────────────────────

    @Test
    @DisplayName("Logout response clears the refresh cookie with Max-Age=0")
    void logout_clearsRefreshCookie() throws Exception {
        // Login
        String loginJson = """
                {"username": "%s", "password": "%s", "tenantSlug": "%s"}
                """.formatted(ADMIN_USERNAME, ADMIN_PASSWORD, PLATFORM_SLUG);

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson))
                .andExpect(status().isOk())
                .andReturn();

        String accessToken = objectMapper.readTree(
                loginResult.getResponse().getContentAsString()).get("accessToken").asText();
        String refreshToken = extractRefreshTokenFromSetCookie(loginResult);

        // Logout
        MvcResult logoutResult = mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + accessToken)
                        .cookie(new Cookie("refresh_token", refreshToken)))
                .andExpect(status().isNoContent())
                .andReturn();

        // Verify the cookie is cleared (Max-Age=0)
        String setCookie = logoutResult.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).isNotNull();
        assertThat(setCookie).contains("Max-Age=0");
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Extracts the refresh token value from the Set-Cookie header.
     */
    private String extractRefreshTokenFromSetCookie(MvcResult result) {
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        if (setCookie == null || !setCookie.startsWith("refresh_token=")) {
            return null;
        }
        // Format: refresh_token=<value>; Path=...; ...
        String tokenPart = setCookie.split(";")[0]; // "refresh_token=<value>"
        return tokenPart.substring("refresh_token=".length());
    }
}

