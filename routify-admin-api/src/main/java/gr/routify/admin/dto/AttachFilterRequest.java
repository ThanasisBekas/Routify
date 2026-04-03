package gr.routify.admin.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Typed request body for attaching a filter to a route.
 */
public record AttachFilterRequest(
        @NotNull UUID filterId,
        int order,
        String phase
) {}

