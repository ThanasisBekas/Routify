package io.routify.identity.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.routify.common.domain.TenantPlan;
import io.routify.common.domain.UserRole;
import io.routify.identity.controller.AuthController;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/** Auth API DTOs */
public final class AuthDto {

    private AuthDto() {}

    // ─── Requests ─────────────────────────────────────────────────────────────

    public record LoginRequest(
            @NotBlank String username,
            @NotBlank String password,
            @NotBlank String tenantSlug
    ) {}

    public record RefreshRequest(
            @NotBlank String refreshToken
    ) {}

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 8) String newPassword
    ) {}

    // ─── Responses ────────────────────────────────────────────────────────────

    public record LoginResponse(
            String accessToken,
            /** Internal only — NEVER serialised in HTTP responses. Delivered via HttpOnly cookie. */
            @JsonIgnore String refreshToken,
            String tokenType,
            long expiresIn,
            UserInfo user,
            /** True when the account requires an immediate password change before further use. */
            boolean mustChangePassword
    ) {
        /**
         * Returns a copy of this response with {@code refreshToken} set to {@code null}.
         * Use this when writing the HTTP response body — the token is delivered via
         * the {@code HttpOnly} cookie set in {@link AuthController}.
         */
        public LoginResponse withoutRefreshToken() {
            return new LoginResponse(accessToken, null, tokenType, expiresIn, user, mustChangePassword);
        }
    }

    public record UserInfo(
            UUID id,
            UUID tenantId,
            String username,
            String email,
            UserRole role,
            boolean mustChangePassword
    ) {}

    // ─── User CRUD ────────────────────────────────────────────────────────────

    public record CreateUserRequest(
            @NotBlank @Size(min = 3, max = 100) String username,
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, max = 100) String password,
            @NotNull UserRole role
    ) {}

    public record UpdateUserRequest(
            @Size(min = 3, max = 100) String username,
            @Email String email,
            UserRole role
    ) {}

    public record UserResponse(
            UUID id,
            UUID tenantId,
            String username,
            String email,
            UserRole role,
            String status,
            boolean mustChangePassword,
            Instant lastLoginAt,
            Instant createdAt
    ) {}

    // ─── Tenant CRUD ──────────────────────────────────────────────────────────

    public record CreateTenantRequest(
            @NotBlank @Size(min = 3, max = 255) String name,
            @NotBlank @Size(min = 3, max = 100) String slug,
            @NotNull TenantPlan plan,
            @Email String contactEmail
    ) {}

    public record TenantResponse(
            UUID id,
            String name,
            String slug,
            String status,
            TenantPlan plan,
            String contactEmail,
            Instant createdAt
    ) {}
}

