package io.routify.route.dto;

import io.routify.common.domain.FilterType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Filter Definition API DTOs */
public final class FilterDefinitionDto {

    private FilterDefinitionDto() {}

    /**
     * A reference to a gateway configuration entry (auth provider, rate limit policy, etc.)
     * that supplies the authoritative settings for this filter definition.
     *
     * @param refType  e.g. "AUTH_PROVIDER", "RATE_LIMIT_POLICY", "CIRCUIT_BREAKER_DEFAULTS",
     *                 "RESILIENCE_DEFAULTS", "TLS_SOURCE", "DOWNSTREAM_CREDENTIAL"
     * @param refId    the id of the gateway config entry
     * @param refName  human-readable name (cached for display only, not authoritative)
     */
    public record GatewayConfigRef(
            @NotBlank String refType,
            @NotBlank String refId,
            String refName
    ) {}

    public record CreateRequest(
            @NotBlank @Size(min = 2, max = 255) String name,
            @Size(max = 1000)                   String description,
            @NotNull                            FilterType filterType,
            @NotNull                            Map<String, Object> config,
            GatewayConfigRef                    gatewayConfigRef
    ) {}

    public record UpdateRequest(
            @Size(min = 2, max = 255) String name,
            @Size(max = 1000)         String description,
            Map<String, Object>       config,
            GatewayConfigRef          gatewayConfigRef
    ) {}

    public record Response(
            UUID id,
            UUID tenantId,
            String name,
            String description,
            FilterType filterType,
            Map<String, Object> config,
            Boolean systemManaged,
            Boolean enabled,
            Integer usageCount,
            GatewayConfigRef gatewayConfigRef,
            String createdBy,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record Summary(
            UUID id,
            String name,
            FilterType filterType,
            Boolean enabled,
            Integer usageCount,
            GatewayConfigRef gatewayConfigRef,
            Instant createdAt
    ) {}
}
