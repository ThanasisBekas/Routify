package io.routify.identity.service;

import io.routify.common.exception.RoutifyException;
import io.routify.common.security.RedisKeys;
import io.routify.identity.domain.AppUser;
import io.routify.identity.domain.Tenant;
import io.routify.identity.dto.AuthDto;
import io.routify.identity.repository.TenantRepository;
import io.routify.identity.repository.UserRepository;
import io.routify.identity.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Authentication service — handles login, token refresh, and password changes.
 *
 * <p>Login flow:
 * <ol>
 *   <li>Resolve tenant by slug</li>
 *   <li>Find user by username within tenant</li>
 *   <li>Verify password (BCrypt)</li>
 *   <li>Check account is active and not locked</li>
 *   <li>Issue access + refresh JWT tokens</li>
 *   <li>Update lastLoginAt</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redisTemplate;

    @Transactional
    public AuthDto.LoginResponse login(AuthDto.LoginRequest request) {
        // Resolve tenant
        Tenant tenant = tenantRepository.findBySlug(request.tenantSlug())
                .orElseThrow(() -> new RoutifyException.Unauthorized("Invalid credentials"));

        if (!tenant.isActive()) {
            throw new RoutifyException.Forbidden("Tenant is suspended");
        }

        // Find user
        AppUser user = userRepository
                .findByUsernameAndTenantId(request.username(), tenant.getId())
                .orElseThrow(() -> new RoutifyException.Unauthorized("Invalid credentials"));

        // Check locked
        if (user.isLocked()) {
            throw new RoutifyException.Unauthorized("Account is temporarily locked. Please try again later.");
        }

        if (!user.isActive()) {
            throw new RoutifyException.Unauthorized("Account is not active");
        }

        // Verify password
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            user.recordFailedLogin();
            userRepository.save(user);
            log.warn("Failed login attempt for user={} tenant={}", request.username(), request.tenantSlug());
            throw new RoutifyException.Unauthorized("Invalid credentials");
        }

        // Success
        user.recordSuccessfulLogin();
        userRepository.save(user);

        String accessToken  = jwtService.issueAccessToken(user);
        String refreshToken = jwtService.issueRefreshToken(user);

        log.info("User logged in: username={} tenant={}", user.getUsername(), tenant.getSlug());

        return new AuthDto.LoginResponse(
                accessToken,
                refreshToken,
                "Bearer",
                jwtService.getAccessTokenTtlSeconds(),
                new AuthDto.UserInfo(
                        user.getId(), user.getTenantId(),
                        user.getUsername(), user.getEmail(), user.getRole(),
                        user.isMustChangePassword()),
                user.isMustChangePassword()
        );
    }

    @Transactional
    public AuthDto.LoginResponse refresh(AuthDto.RefreshRequest request) {
        try {
            var claims = jwtService.validateAndParseClaims(request.refreshToken());

            if (!"REFRESH".equals(claims.get("type"))) {
                throw new RoutifyException.Unauthorized("Not a refresh token");
            }

            // Check blacklist — token may have been revoked via logout
            String jti = claims.getId();
            if (jti != null && redisTemplate.hasKey(RedisKeys.BLOCKLIST_PREFIX + jti)) {
                throw new RoutifyException.Unauthorized("Token has been revoked");
            }

            AppUser user = userRepository.findById(
                    java.util.UUID.fromString(claims.getSubject()))
                    .orElseThrow(() -> new RoutifyException.Unauthorized("Invalid or expired refresh token"));

            if (!user.isActive()) {
                throw new RoutifyException.Unauthorized("Account is not active");
            }

            String newAccessToken  = jwtService.issueAccessToken(user);
            String newRefreshToken = jwtService.issueRefreshToken(user);

            return new AuthDto.LoginResponse(
                    newAccessToken, newRefreshToken, "Bearer",
                    jwtService.getAccessTokenTtlSeconds(),
                    new AuthDto.UserInfo(user.getId(), user.getTenantId(),
                            user.getUsername(), user.getEmail(), user.getRole(),
                            user.isMustChangePassword()),
                    user.isMustChangePassword());

        } catch (RoutifyException e) {
            throw e;
        } catch (Exception e) {
            throw new RoutifyException.Unauthorized("Invalid or expired refresh token");
        }
    }

    /**
     * Self-service password change.
     *
     * <p>The caller must supply their current password for re-authentication.
     * On success the {@code mustChangePassword} flag is cleared, which unblocks
     * users who were forced to change their password on first login.
     *
     * @param userId          UUID of the authenticated user (from JWT)
     * @param currentPassword the user's current (plain-text) password
     * @param newPassword     the desired new (plain-text) password (min 8 chars)
     */
    @Transactional
    public void changePassword(java.util.UUID userId, String currentPassword, String newPassword) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new RoutifyException.NotFound("User", userId.toString()));

        if (!user.isActive()) {
            throw new RoutifyException.Unauthorized("Account is not active");
        }

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new RoutifyException.Unauthorized("Current password is incorrect");
        }

        if (newPassword == null || newPassword.length() < 8) {
            throw new RoutifyException.Validation("New password must be at least 8 characters");
        }

        user.changePassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        log.info("Password changed for userId={}", userId);
    }

    /**
     * Admin-initiated forced password change for a specific user.
     *
     * <p>Sets a new temporary password and flags {@code mustChangePassword=true},
     * so the user is forced to change it on next login.
     * The caller must be an admin (enforced by the controller layer).
     *
     * @param targetUserId UUID of the target user
     * @param tenantId     tenant of the target user (scoping guard)
     * @param newPassword  the temporary plain-text password to set
     */
    @Transactional
    public void adminResetPassword(java.util.UUID targetUserId, java.util.UUID tenantId, String newPassword) {
        AppUser user = userRepository.findByIdAndTenantId(targetUserId, tenantId)
                .orElseThrow(() -> new RoutifyException.NotFound("User", targetUserId.toString()));

        if (newPassword == null || newPassword.length() < 8) {
            throw new RoutifyException.Validation("New password must be at least 8 characters");
        }

        user.adminResetPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        log.info("Admin reset password for userId={} in tenantId={}", targetUserId, tenantId);
    }
    public void revokeRefreshToken(String rawRefreshToken) {
        try {
            var claims = jwtService.validateAndParseClaims(rawRefreshToken);
            String jti = claims.getId();
            if (jti != null) {
                redisTemplate.opsForValue().set(
                        RedisKeys.BLOCKLIST_PREFIX + jti,
                        "1",
                        Duration.ofSeconds(jwtService.getRefreshTokenTtlSeconds()));
                log.debug("Refresh token revoked: jti={}", jti);
            }
        } catch (Exception e) {
            // Token may already be expired — that's fine; it can't be used anyway
            log.debug("Could not revoke refresh token (likely already expired): {}", e.getMessage());
        }
    }

    /**
     * Revokes an access token by adding its {@code jti} to the Redis blocklist with a
     * TTL equal to the token's remaining lifetime.  Called on logout.
     *
     * <p>The API gateway checks this blocklist via {@code JwtAuthGatewayFilterFactory}
     * on every incoming request (see Phase 3.2 — token blocklist).
     */
    public void revokeAccessToken(String rawAccessToken) {
        try {
            var claims = jwtService.validateAndParseClaims(rawAccessToken);
            String jti = claims.getId();
            if (jti != null) {
                long remainingTtl = claims.getExpiration().toInstant()
                        .getEpochSecond() - Instant.now().getEpochSecond();
                if (remainingTtl > 0) {
                    redisTemplate.opsForValue().set(
                            RedisKeys.BLOCKLIST_PREFIX + jti,
                            "1",
                            Duration.ofSeconds(remainingTtl));
                    log.debug("Access token revoked: jti={} ttl={}s", jti, remainingTtl);
                }
            }
        } catch (Exception e) {
            log.debug("Could not revoke access token (likely already expired): {}", e.getMessage());
        }
    }
}

