package io.routify.admin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Typed request body for creating a new tenant.
 */
public record CreateTenantRequest(
        @NotBlank String name,
        @NotBlank String slug,
        String plan,
        @Email String contactEmail
) {}

