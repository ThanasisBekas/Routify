package io.routify.admin.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateApiKeyRequest(
        @NotBlank String name,
        String role,     // defaults to OPERATOR
        String email,
        String expiresAt // ISO-8601 instant, nullable
) {}

