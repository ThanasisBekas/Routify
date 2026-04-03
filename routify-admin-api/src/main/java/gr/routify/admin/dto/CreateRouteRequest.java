package gr.routify.admin.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * Typed request body for creating a new route.
 * Replaces the previous {@code Map<String,Object>} pattern in {@link gr.routify.admin.controller.AdminRoutesController}.
 */
public record CreateRouteRequest(
        @NotBlank String name,
        String description,
        @NotBlank String pathPattern,
        String methods,
        @NotBlank String upstreamUri,
        String stripPrefix,
        Map<String, Object> extraConfig
) {}

