package io.routify.admin.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * Typed request body for creating a new filter definition.
 */
public record CreateFilterRequest(
        @NotBlank String name,
        String description,
        @NotBlank String filterType,
        Map<String, Object> config,
        Map<String, Object> gatewayConfigRef
) {}

