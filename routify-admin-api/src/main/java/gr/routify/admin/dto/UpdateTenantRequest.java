package gr.routify.admin.dto;

/**
 * Typed request body for updating an existing tenant.
 */
public record UpdateTenantRequest(
        String name,
        String plan,
        String contactEmail
) {}

