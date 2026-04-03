package gr.routify.admin.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Typed request body for creating a new certificate group.
 */
public record CreateCertGroupRequest(
        @NotBlank String logicalId,
        @NotBlank String alias,
        String description
) {}

