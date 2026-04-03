package gr.routify.admin.dto;

import java.util.Map;

/**
 * Typed request body for updating an existing route.
 */
public record UpdateRouteRequest(
        String name,
        String description,
        String pathPattern,
        String methods,
        String upstreamUri,
        String stripPrefix,
        Map<String, Object> extraConfig
) {}

