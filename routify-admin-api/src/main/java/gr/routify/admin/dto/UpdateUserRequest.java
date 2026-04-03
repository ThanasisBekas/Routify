package gr.routify.admin.dto;

/**
 * Typed request body for updating an existing user.
 */
public record UpdateUserRequest(
        String username,
        String email,
        String role
) {}

