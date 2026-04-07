package io.routify.route.dto;

import io.routify.common.domain.RouteEnvironment;
import io.routify.common.domain.RouteStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Route API DTOs using Java 21 records for immutability.
 * Nested records group related request/response types.
 */
public final class RouteDto {

    private RouteDto() {}

    // ─── Request Records ──────────────────────────────────────────────────────

    public record CreateRequest(
            @NotBlank @Size(min = 3, max = 255)
            String name,

            @Size(max = 1000)
            String description,

            @NotBlank @Size(max = 500)
            @Pattern(regexp = "^/.*", message = "Path pattern must start with /")
            String pathPattern,

            @NotBlank
            String methods,

            @NotBlank @Size(max = 500)
            @Pattern(regexp = "https?://.+|lb://.+", message = "Upstream URI must be http(s):// or lb://")
            String upstreamUri,

            @Size(max = 200)
            String stripPrefix,

            Map<String, Object> extraConfig,

            RouteEnvironment environment
    ) {}

    public record UpdateRequest(
            @Size(min = 3, max = 255)
            String name,

            @Size(max = 1000)
            String description,

            @Size(max = 500)
            @Pattern(regexp = "^/.*|^$", message = "Path pattern must start with /")
            String pathPattern,

            String methods,

            @Size(max = 500)
            @Pattern(regexp = "https?://.+|lb://.+|^$", message = "Upstream URI must be http(s):// or lb://")
            String upstreamUri,

            @Size(max = 200)
            String stripPrefix,

            Map<String, Object> extraConfig
    ) {}

    public record AttachFilterRequest(
            @NotNull UUID filterId,
            int order,
            @NotBlank @Pattern(regexp = "PRE|POST") String phase
    ) {}

    // ─── Response Records ─────────────────────────────────────────────────────

    public record Response(
            UUID id,
            UUID tenantId,
            String name,
            String description,
            String pathPattern,
            String methods,
            String upstreamUri,
            String stripPrefix,
            RouteStatus status,
            RouteEnvironment environment,
            Integer version,
            List<FilterRef> filters,
            Map<String, Object> extraConfig,
            String createdBy,
            Instant createdAt,
            Instant updatedAt,
            Instant activatedAt,
            int trafficWeight,
            UUID canaryRouteId,
            java.math.BigDecimal canaryAutoRollbackThreshold
    ) {}

    public record FilterRef(
            UUID filterId,
            String filterName,
            String filterType,
            int order,
            String phase,
            boolean enabled
    ) {}

    /** Minimal route representation for list views */
    public record Summary(
            UUID id,
            String name,
            String description,
            String pathPattern,
            String methods,
            String upstreamUri,
            RouteStatus status,
            RouteEnvironment environment,
            Integer version,
            int filterCount,
            Instant createdAt,
            Instant activatedAt,
            int trafficWeight,
            UUID canaryRouteId
    ) {}

    /**
     * Gateway snapshot payload — consumed by routify-api-gateway
     * to build the RouteDefinition. Includes all filter configs for
     * the filter chain construction.
     */
    public record GatewaySnapshot(
            UUID routeId,
            UUID tenantId,
            String name,
            String pathPattern,
            String methods,
            String upstreamUri,
            String stripPrefix,
            Integer version,
            String environment,
            List<FilterSnapshot> filters,
            Map<String, Object> extraConfig,
            int trafficWeight,
            UUID canaryRouteId
    ) {
        public record FilterSnapshot(
                UUID filterId,
                String filterType,
                int order,
                String phase,
                Map<String, Object> config,
                /** Optional reference to a gateway configuration entry (auth provider, rate limit policy, etc.) */
                Map<String, Object> gatewayConfigRef
        ) {}
    }
}

