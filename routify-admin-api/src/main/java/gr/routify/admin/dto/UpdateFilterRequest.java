package gr.routify.admin.dto;

import java.util.Map;

/**
 * Typed request body for updating a filter definition.
 */
public record UpdateFilterRequest(
        String name,
        String description,
        Map<String, Object> config,
        Map<String, Object> gatewayConfigRef
) {}

