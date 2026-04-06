package io.routify.gateway.routing;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Data Transfer Object representing a route snapshot from routify-route-service.
 * This is deserialized from the {@code /api/v1/routes/gateway/snapshot} endpoint
 * and from the Redis cache.
 */
public record RouteSnapshotDto(
        @JsonProperty("routeId")     UUID routeId,
        @JsonProperty("tenantId")    UUID tenantId,
        @JsonProperty("name")        String name,
        @JsonProperty("pathPattern") String pathPattern,
        @JsonProperty("methods")     String methods,
        @JsonProperty("upstreamUri") String upstreamUri,
        @JsonProperty("stripPrefix") String stripPrefix,
        @JsonProperty("version")     Integer version,
        @JsonProperty("environment") String environment,
        @JsonProperty("filters")     List<FilterSnapshotDto> filters,
        @JsonProperty("extraConfig") Map<String, Object> extraConfig
) {
    public record FilterSnapshotDto(
            @JsonProperty("filterId")          UUID filterId,
            @JsonProperty("filterType")        String filterType,
            @JsonProperty("order")             int order,
            @JsonProperty("phase")             String phase,
            @JsonProperty("config")            Map<String, Object> config,
            /** Optional reference to a gateway config entry that supplies authoritative settings. */
            @JsonProperty("gatewayConfigRef")  Map<String, Object> gatewayConfigRef
    ) {}
}

