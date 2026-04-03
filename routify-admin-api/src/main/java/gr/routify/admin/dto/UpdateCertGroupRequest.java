package gr.routify.admin.dto;

/**
 * Typed request body for updating a certificate group.
 */
public record UpdateCertGroupRequest(
        String alias,
        String description
) {}

