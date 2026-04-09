package io.routify.admin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Typed request body for creating a new user.
 */
public record CreateUserRequest(
        @NotBlank String username,
        @Email String email,
        @NotBlank String password,
        String role,
        String roleId
) {}

