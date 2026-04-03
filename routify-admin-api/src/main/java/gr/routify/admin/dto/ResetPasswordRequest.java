package gr.routify.admin.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Typed request body for admin-initiated password reset.
 */
public record ResetPasswordRequest(@NotBlank String newPassword) {}

