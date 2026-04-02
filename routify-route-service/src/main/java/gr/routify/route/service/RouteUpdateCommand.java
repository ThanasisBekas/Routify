package gr.routify.route.service;

import java.util.Map;

/**
 * Immutable command record for updating a route.
 * Uses Java 21 records — null fields mean "no change".
 */
public record RouteUpdateCommand(
        String name,
        String description,
        String pathPattern,
        String methods,
        String upstreamUri,
        String stripPrefix,
        Map<String, Object> extraConfig
) {}

