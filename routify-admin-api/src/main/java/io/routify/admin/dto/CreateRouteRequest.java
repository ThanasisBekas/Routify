package io.routify.admin.dto;

import io.routify.admin.controller.AdminRoutesController;
import io.routify.common.domain.RouteEnvironment;
import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * Typed request body for creating a new route.
 * Replaces the previous {@code Map<String,Object>} pattern in {@link AdminRoutesController}.
 */
public record CreateRouteRequest(
        @NotBlank String name,
        String description,
        @NotBlank String pathPattern,
        String methods,
        @NotBlank String upstreamUri,
        String stripPrefix,
        Map<String, Object> extraConfig,
        RouteEnvironment environment
) {}

