package io.routify.common.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;
import java.util.UUID;

/**
 * Sealed interface representing all domain events published to Kafka.
 * Uses Java 21 sealed classes + records for type-safe, exhaustive pattern matching.
 *
 * <p>Each event carries:
 * <ul>
 *   <li>{@code eventId} — idempotency key (UUID)</li>
 *   <li>{@code tenantId} — tenant scope</li>
 *   <li>{@code occurredAt} — event timestamp</li>
 *   <li>{@code correlationId} — request tracing</li>
 * </ul>
 *
 * <p>Consumer usage with pattern matching:
 * <pre>{@code
 * switch (event) {
 *     case RouteCreated rc  -> handleRouteCreated(rc);
 *     case RouteActivated ra -> handleRouteActivated(ra);
 *     case RouteDeleted rd   -> handleRouteDeleted(rd);
 *     case FilterAttached fa -> handleFilterAttached(fa);
 *     // ...
 * }
 * }</pre>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", defaultImpl = DomainEvent.Unknown.class)
@JsonSubTypes({
    @JsonSubTypes.Type(value = DomainEvent.RouteCreated.class,      name = "ROUTE_CREATED"),
    @JsonSubTypes.Type(value = DomainEvent.RouteCloned.class,       name = "ROUTE_CLONED"),
    @JsonSubTypes.Type(value = DomainEvent.RouteUpdated.class,      name = "ROUTE_UPDATED"),
    @JsonSubTypes.Type(value = DomainEvent.RouteActivated.class,    name = "ROUTE_ACTIVATED"),
    @JsonSubTypes.Type(value = DomainEvent.RouteDeactivated.class,  name = "ROUTE_DEACTIVATED"),
    @JsonSubTypes.Type(value = DomainEvent.RouteDeleted.class,      name = "ROUTE_DELETED"),
    @JsonSubTypes.Type(value = DomainEvent.FilterCreated.class,     name = "FILTER_CREATED"),
    @JsonSubTypes.Type(value = DomainEvent.FilterUpdated.class,     name = "FILTER_UPDATED"),
    @JsonSubTypes.Type(value = DomainEvent.FilterDeleted.class,     name = "FILTER_DELETED"),
    @JsonSubTypes.Type(value = DomainEvent.FilterAttached.class,    name = "FILTER_ATTACHED"),
    @JsonSubTypes.Type(value = DomainEvent.FilterDetached.class,    name = "FILTER_DETACHED"),
    @JsonSubTypes.Type(value = DomainEvent.TenantCreated.class,     name = "TENANT_CREATED"),
    @JsonSubTypes.Type(value = DomainEvent.TenantUpdated.class,     name = "TENANT_UPDATED"),
    @JsonSubTypes.Type(value = DomainEvent.TenantSuspended.class,   name = "TENANT_SUSPENDED"),
    @JsonSubTypes.Type(value = DomainEvent.UserCreated.class,       name = "USER_CREATED"),
    @JsonSubTypes.Type(value = DomainEvent.UserUpdated.class,       name = "USER_UPDATED"),
    @JsonSubTypes.Type(value = DomainEvent.UserDeleted.class,       name = "USER_DELETED"),
    @JsonSubTypes.Type(value = DomainEvent.CertRotated.class,                  name = "CERT_ROTATED"),
    @JsonSubTypes.Type(value = DomainEvent.CertificateUploaded.class,          name = "CERTIFICATE_UPLOADED"),
    @JsonSubTypes.Type(value = DomainEvent.CertificateRevoked.class,           name = "CERTIFICATE_REVOKED"),
    @JsonSubTypes.Type(value = DomainEvent.CertificateDeleted.class,           name = "CERTIFICATE_DELETED"),
    @JsonSubTypes.Type(value = DomainEvent.CertificateMappedToGateway.class,   name = "CERTIFICATE_MAPPED_TO_GATEWAY"),
    @JsonSubTypes.Type(value = DomainEvent.CertificateUnmappedFromGateway.class, name = "CERTIFICATE_UNMAPPED_FROM_GATEWAY"),
    @JsonSubTypes.Type(value = DomainEvent.CertGroupCreated.class,             name = "CERT_GROUP_CREATED"),
    @JsonSubTypes.Type(value = DomainEvent.CertGroupUpdated.class,             name = "CERT_GROUP_UPDATED"),
    @JsonSubTypes.Type(value = DomainEvent.CertGroupArchived.class,            name = "CERT_GROUP_ARCHIVED"),
    @JsonSubTypes.Type(value = DomainEvent.CertGroupDeleted.class,             name = "CERT_GROUP_DELETED"),
    @JsonSubTypes.Type(value = DomainEvent.CertAddedToGroup.class,             name = "CERT_ADDED_TO_GROUP"),
    @JsonSubTypes.Type(value = DomainEvent.CertRemovedFromGroup.class,         name = "CERT_REMOVED_FROM_GROUP"),
    @JsonSubTypes.Type(value = DomainEvent.GatewayReloadRequested.class, name = "GATEWAY_RELOAD_REQUESTED"),
    @JsonSubTypes.Type(value = DomainEvent.GatewayConfigChanged.class,   name = "GATEWAY_CONFIG_CHANGED"),
    // ─── API Key Events ────────────────────────────────────────────────────────
    @JsonSubTypes.Type(value = DomainEvent.ApiKeyCreated.class,  name = "API_KEY_CREATED"),
    @JsonSubTypes.Type(value = DomainEvent.ApiKeyRevoked.class,  name = "API_KEY_REVOKED"),
})
public sealed interface DomainEvent
        permits
            DomainEvent.RouteCreated,
            DomainEvent.RouteCloned,
            DomainEvent.RouteUpdated,
            DomainEvent.RouteActivated,
            DomainEvent.RouteDeactivated,
            DomainEvent.RouteDeleted,
            DomainEvent.FilterCreated,
            DomainEvent.FilterUpdated,
            DomainEvent.FilterDeleted,
            DomainEvent.FilterAttached,
            DomainEvent.FilterDetached,
            DomainEvent.TenantCreated,
            DomainEvent.TenantUpdated,
            DomainEvent.TenantSuspended,
            DomainEvent.UserCreated,
            DomainEvent.UserUpdated,
            DomainEvent.UserDeleted,
            DomainEvent.CertRotated,
            DomainEvent.CertificateUploaded,
            DomainEvent.CertificateRevoked,
            DomainEvent.CertificateDeleted,
            DomainEvent.CertificateMappedToGateway,
            DomainEvent.CertificateUnmappedFromGateway,
            DomainEvent.CertGroupCreated,
            DomainEvent.CertGroupUpdated,
            DomainEvent.CertGroupArchived,
            DomainEvent.CertGroupDeleted,
            DomainEvent.CertAddedToGroup,
            DomainEvent.CertRemovedFromGroup,
            DomainEvent.GatewayReloadRequested,
            DomainEvent.GatewayConfigChanged,
            DomainEvent.ApiKeyCreated,
            DomainEvent.ApiKeyRevoked,
            DomainEvent.Unknown {

    UUID eventId();
    UUID tenantId();
    Instant occurredAt();
    String correlationId();
    /** User ID or service name that triggered the event. May be null for system-initiated events. */
    String actor();

    // ─── Route Events ─────────────────────────────────────────────────────────

    record RouteCreated(
            UUID eventId,
            UUID tenantId,
            UUID routeId,
            String routeName,
            String path,
            String method,
            Instant occurredAt,
            String correlationId,
            String actor
    ) implements DomainEvent {}

    record RouteCloned(
            UUID eventId,
            UUID tenantId,
            UUID sourceRouteId,
            UUID clonedRouteId,
            String clonedRouteName,
            Instant occurredAt,
            String correlationId,
            String actor
    ) implements DomainEvent {}

    record RouteUpdated(
            UUID eventId,
            UUID tenantId,
            UUID routeId,
            String routeName,
            Instant occurredAt,
            String correlationId,
            String actor
    ) implements DomainEvent {}

    record RouteActivated(
            UUID eventId,
            UUID tenantId,
            UUID routeId,
            String routeName,
            String path,
            String method,
            String upstreamUri,
            Instant occurredAt,
            String correlationId,
            String actor
    ) implements DomainEvent {}

    record RouteDeactivated(
            UUID eventId,
            UUID tenantId,
            UUID routeId,
            String routeName,
            Instant occurredAt,
            String correlationId,
            String actor
    ) implements DomainEvent {}

    record RouteDeleted(
            UUID eventId,
            UUID tenantId,
            UUID routeId,
            Instant occurredAt,
            String correlationId,
            String actor
    ) implements DomainEvent {}

    // ─── Filter Events ────────────────────────────────────────────────────────

    record FilterCreated(
            UUID eventId,
            UUID tenantId,
            UUID filterId,
            String filterName,
            String filterType,
            Instant occurredAt,
            String correlationId,
            String actor
    ) implements DomainEvent {}

    record FilterUpdated(
            UUID eventId,
            UUID tenantId,
            UUID filterId,
            String filterName,
            Instant occurredAt,
            String correlationId,
            String actor
    ) implements DomainEvent {}

    record FilterDeleted(
            UUID eventId,
            UUID tenantId,
            UUID filterId,
            Instant occurredAt,
            String correlationId,
            String actor
    ) implements DomainEvent {}

    record FilterAttached(
            UUID eventId,
            UUID tenantId,
            UUID routeId,
            UUID filterId,
            int order,
            String phase,
            Instant occurredAt,
            String correlationId,
            String actor
    ) implements DomainEvent {}

    record FilterDetached(
            UUID eventId,
            UUID tenantId,
            UUID routeId,
            UUID filterId,
            Instant occurredAt,
            String correlationId,
            String actor
    ) implements DomainEvent {}

    // ─── Tenant Events ────────────────────────────────────────────────────────

    record TenantCreated(
            UUID eventId,
            UUID tenantId,
            String tenantName,
            String slug,
            String plan,
            Instant occurredAt,
            String correlationId,
            String actor
    ) implements DomainEvent {}

    record TenantUpdated(
            UUID eventId,
            UUID tenantId,
            String tenantName,
            Instant occurredAt,
            String correlationId,
            String actor
    ) implements DomainEvent {}

    record TenantSuspended(
            UUID eventId,
            UUID tenantId,
            String reason,
            Instant occurredAt,
            String correlationId,
            String actor
    ) implements DomainEvent {}

    // ─── User Events ──────────────────────────────────────────────────────────

    record UserCreated(
            UUID eventId,
            UUID tenantId,
            UUID userId,
            String username,
            String email,
            String role,
            Instant occurredAt,
            String correlationId,
            String actor
    ) implements DomainEvent {}

    record UserUpdated(
            UUID eventId,
            UUID tenantId,
            UUID userId,
            String username,
            Instant occurredAt,
            String correlationId,
            String actor
    ) implements DomainEvent {}

    record UserDeleted(
            UUID eventId,
            UUID tenantId,
            UUID userId,
            Instant occurredAt,
            String correlationId,
            String actor
    ) implements DomainEvent {}

    // ─── Certificate Events ───────────────────────────────────────────────────

    /** Published by routify-cert-vault when a certificate is successfully uploaded. */
    record CertificateUploaded(
            UUID eventId,
            UUID tenantId,
            UUID certId,
            String logicalId,
            String alias,
            String status,
            UUID groupId,
            String groupLogicalId,
            String memberAlias,
            String effectiveGatewayLogicalId,
            Instant occurredAt,
            String correlationId,
            String actor
    ) implements DomainEvent {}

    /** Published by routify-cert-vault when a certificate is revoked. */
    record CertificateRevoked(
            UUID eventId,
            UUID tenantId,
            UUID certId,
            String logicalId,
            String alias,
            String gatewayTlsLogicalId,
            String effectiveGatewayLogicalId,
            Instant occurredAt,
            String correlationId,
            String actor
    ) implements DomainEvent {}

    /** Published by routify-cert-vault when a certificate is deleted. */
    record CertificateDeleted(
            UUID eventId,
            UUID tenantId,
            UUID certId,
            String logicalId,
            String alias,
            String gatewayTlsLogicalId,
            String effectiveGatewayLogicalId,
            Instant occurredAt,
            String correlationId,
            String actor
    ) implements DomainEvent {}

    /** Published by routify-cert-vault when a certificate is mapped to a gateway TLS logical ID. */
    record CertificateMappedToGateway(
            UUID eventId,
            UUID tenantId,
            UUID certId,
            String logicalId,
            String alias,
            String gatewayTlsLogicalId,
            UUID groupId,
            String groupLogicalId,
            String effectiveGatewayLogicalId,
            String memberAlias,
            Instant occurredAt,
            String correlationId,
            String actor
    ) implements DomainEvent {}

    /** Published by routify-cert-vault when a certificate is unmapped from a gateway TLS logical ID. */
    record CertificateUnmappedFromGateway(
            UUID eventId,
            UUID tenantId,
            UUID certId,
            String logicalId,
            String alias,
            String gatewayTlsLogicalId,
            String effectiveGatewayLogicalId,
            Instant occurredAt,
            String correlationId,
            String actor
    ) implements DomainEvent {}

    // ─── Certificate Group Events ─────────────────────────────────────────────

    /** Published by routify-cert-vault when a certificate group is created. */
    record CertGroupCreated(
            UUID eventId,
            UUID tenantId,
            UUID groupId,
            String logicalId,
            String alias,
            String status,
            Instant occurredAt,
            String correlationId,
            String actor
    ) implements DomainEvent {}

    /** Published by routify-cert-vault when a certificate group is updated. */
    record CertGroupUpdated(
            UUID eventId,
            UUID tenantId,
            UUID groupId,
            String logicalId,
            String alias,
            String status,
            Instant occurredAt,
            String correlationId,
            String actor
    ) implements DomainEvent {}

    /** Published by routify-cert-vault when a certificate group is archived. */
    record CertGroupArchived(
            UUID eventId,
            UUID tenantId,
            UUID groupId,
            String logicalId,
            String alias,
            Instant occurredAt,
            String correlationId,
            String actor
    ) implements DomainEvent {}

    /** Published by routify-cert-vault when a certificate group is deleted. */
    record CertGroupDeleted(
            UUID eventId,
            UUID tenantId,
            UUID groupId,
            String logicalId,
            Instant occurredAt,
            String correlationId,
            String actor
    ) implements DomainEvent {}

    /** Published by routify-cert-vault when a certificate is added to a group. */
    record CertAddedToGroup(
            UUID eventId,
            UUID tenantId,
            UUID groupId,
            String groupLogicalId,
            UUID certId,
            String certLogicalId,
            String certAlias,
            String memberAlias,
            String certStatus,
            Instant occurredAt,
            String correlationId,
            String actor
    ) implements DomainEvent {}

    /** Published by routify-cert-vault when a certificate is removed from a group. */
    record CertRemovedFromGroup(
            UUID eventId,
            UUID tenantId,
            UUID groupId,
            String groupLogicalId,
            UUID certId,
            String certLogicalId,
            String certAlias,
            String memberAlias,
            Instant occurredAt,
            String correlationId,
            String actor
    ) implements DomainEvent {}

    // ─── Infrastructure Events ────────────────────────────────────────────────

    /** @deprecated Use the specific {@link CertificateRevoked}/{@link CertificateMappedToGateway} subtypes. */
    @Deprecated
    record CertRotated(
            UUID eventId,
            UUID tenantId,
            UUID routeId,
            String certFingerprint,
            Instant expiresAt,
            Instant occurredAt,
            String correlationId,
            String actor
    ) implements DomainEvent {}

    record GatewayReloadRequested(
            UUID eventId,
            UUID tenantId,
            String reason,
            Instant occurredAt,
            String correlationId,
            String actor
    ) implements DomainEvent {}

    /**
     * Published by routify-admin-api whenever gateway configuration is saved.
     * All running gateway instances consume this event and reload their config
     * from the database — ensuring rollout-safe, persistent configuration.
     */
    record GatewayConfigChanged(
            UUID eventId,
            UUID tenantId,
            String section,      // which section changed: CORS, SECURITY_HEADERS, etc.
            String changedBy,
            Instant occurredAt,
            String correlationId,
            String actor
    ) implements DomainEvent {}

    // ─── API Key Events ───────────────────────────────────────────────────────

    /** Published by routify-identity-service when an API key is created or rotated. */
    record ApiKeyCreated(
            UUID eventId,
            UUID tenantId,
            UUID apiKeyId,
            String name,
            String keyPrefix,
            String role,
            Instant occurredAt,
            String correlationId,
            String actor
    ) implements DomainEvent {}

    /** Published by routify-identity-service when an API key is revoked. */
    record ApiKeyRevoked(
            UUID eventId,
            UUID tenantId,
            UUID apiKeyId,
            String name,
            String keyPrefix,
            Instant occurredAt,
            String correlationId,
            String actor
    ) implements DomainEvent {}

    /**
     * Fallback subtype used when the {@code "type"} discriminator is absent or unrecognised.
     * Prevents {@link com.fasterxml.jackson.databind.exc.InvalidTypeIdException} from being
     * thrown during deserialisation (e.g. legacy messages or services that haven't yet been
     * rebuilt with the latest {@code routify-common}).
     */
    record Unknown() implements DomainEvent {
        @Override public UUID eventId()        { return null; }
        @Override public UUID tenantId()       { return null; }
        @Override public Instant occurredAt()  { return null; }
        @Override public String correlationId(){ return null; }
        @Override public String actor()        { return null; }
    }
}

