package gr.routify.common.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import gr.routify.common.domain.FilterType;
import gr.routify.common.domain.RouteStatus;
import gr.routify.common.domain.TenantPlan;
import gr.routify.common.domain.UserRole;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sealed interface representing all typed synchronous query responses returned over RabbitMQ.
 *
 * <p>Responses flow from the owning microservice back to routify-admin-api via the
 * Direct Reply-To RPC pattern (see {@link RabbitTopology}). Each {@code @RabbitListener}
 * handler returns a concrete implementation which is serialised to JSON by Jackson and
 * deserialised on the client side using the {@code "type"} discriminator.
 *
 * <h2>Wire format</h2>
 * Each record is serialised with a {@code "type"} field that acts as the
 * self-describing discriminator, enabling polymorphic deserialization.
 *
 * <h2>Handler usage</h2>
 * <pre>{@code
 * @RabbitListener(queues = RabbitTopology.QUEUE_ROUTES_QUERY)
 * public QueryResponse.RoutesPage handleRoutesQuery(QueryRequest.RoutesQuery req) {
 *     var page = routeService.findAll(req.tenantId(), req.status(), req.pageable());
 *     return new QueryResponse.RoutesPage(page.map(routeMapper::toSummary));
 * }
 * }</pre>
 *
 * <h2>Client usage</h2>
 * <pre>{@code
 * QueryResponse.RoutesPage result = rpc(RabbitTopology.RK_ROUTES_QUERY,
 *         new QueryRequest.RoutesQuery(...), QueryResponse.RoutesPage.class);
 * }</pre>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    // ─── routify-route-service ────────────────────────────────────────────────
    @JsonSubTypes.Type(value = QueryResponse.GatewaySnapshotList.class,  name = "GATEWAY_SNAPSHOT_LIST"),
    @JsonSubTypes.Type(value = QueryResponse.GatewayConfig.class,        name = "GATEWAY_CONFIG"),
    @JsonSubTypes.Type(value = QueryResponse.RouteStatsResult.class,     name = "ROUTE_STATS_RESULT"),
    @JsonSubTypes.Type(value = QueryResponse.RoutesPage.class,           name = "ROUTES_PAGE"),
    @JsonSubTypes.Type(value = QueryResponse.RouteDetail.class,          name = "ROUTE_DETAIL"),
    @JsonSubTypes.Type(value = QueryResponse.FiltersPage.class,          name = "FILTERS_PAGE"),
    @JsonSubTypes.Type(value = QueryResponse.FilterDetail.class,         name = "FILTER_DETAIL"),
    // ─── routify-identity-service ─────────────────────────────────────────────
    @JsonSubTypes.Type(value = QueryResponse.LoginResult.class,          name = "LOGIN_RESULT"),
    @JsonSubTypes.Type(value = QueryResponse.PasswordChangeResult.class, name = "PASSWORD_CHANGE_RESULT"),
    @JsonSubTypes.Type(value = QueryResponse.UsersPage.class,            name = "USERS_PAGE"),
    @JsonSubTypes.Type(value = QueryResponse.UserDetail.class,           name = "USER_DETAIL"),
    @JsonSubTypes.Type(value = QueryResponse.TenantsPage.class,          name = "TENANTS_PAGE"),
    @JsonSubTypes.Type(value = QueryResponse.TenantDetail.class,         name = "TENANT_DETAIL"),
    @JsonSubTypes.Type(value = QueryResponse.ActiveWorkspacesList.class, name = "ACTIVE_WORKSPACES_LIST"),
    // ─── routify-audit-service ────────────────────────────────────────────────
    @JsonSubTypes.Type(value = QueryResponse.AuditEventsPage.class,      name = "AUDIT_EVENTS_PAGE"),
    @JsonSubTypes.Type(value = QueryResponse.RequestLogsPage.class,      name = "REQUEST_LOGS_PAGE"),
    @JsonSubTypes.Type(value = QueryResponse.RequestStatsResult.class,   name = "REQUEST_STATS_RESULT"),
    @JsonSubTypes.Type(value = QueryResponse.ReplayStatsResult.class,    name = "REPLAY_STATS_RESULT"),
    @JsonSubTypes.Type(value = QueryResponse.ReplaySingleResult.class,   name = "REPLAY_SINGLE_RESULT"),
    @JsonSubTypes.Type(value = QueryResponse.ReplayBulkResult.class,     name = "REPLAY_BULK_RESULT"),
    // ─── routify-cert-vault ───────────────────────────────────────────────────
    @JsonSubTypes.Type(value = QueryResponse.CertsPage.class,            name = "CERTS_PAGE"),
    @JsonSubTypes.Type(value = QueryResponse.CertDetail.class,           name = "CERT_DETAIL"),
    @JsonSubTypes.Type(value = QueryResponse.CertsList.class,            name = "CERTS_LIST"),
    @JsonSubTypes.Type(value = QueryResponse.CertStatsResult.class,      name = "CERT_STATS_RESULT"),
    @JsonSubTypes.Type(value = QueryResponse.CertGroupsPage.class,       name = "CERT_GROUPS_PAGE"),
    @JsonSubTypes.Type(value = QueryResponse.CertGroupDetail.class,      name = "CERT_GROUP_DETAIL"),
    @JsonSubTypes.Type(value = QueryResponse.CertGroupMembersList.class, name = "CERT_GROUP_MEMBERS_LIST"),
    // ─── routify-api-gateway ─────────────────────────────────────────────────
    @JsonSubTypes.Type(value = QueryResponse.GatewayStatus.class,        name = "GATEWAY_STATUS"),
    @JsonSubTypes.Type(value = QueryResponse.CertRegistrySnapshot.class, name = "CERT_REGISTRY_SNAPSHOT"),
})
public sealed interface QueryResponse
        permits
            QueryResponse.GatewaySnapshotList,
            QueryResponse.GatewayConfig,
            QueryResponse.RouteStatsResult,
            QueryResponse.RoutesPage,
            QueryResponse.RouteDetail,
            QueryResponse.FiltersPage,
            QueryResponse.FilterDetail,
            QueryResponse.LoginResult,
            QueryResponse.PasswordChangeResult,
            QueryResponse.UsersPage,
            QueryResponse.UserDetail,
            QueryResponse.TenantsPage,
            QueryResponse.TenantDetail,
            QueryResponse.ActiveWorkspacesList,
            QueryResponse.AuditEventsPage,
            QueryResponse.RequestLogsPage,
            QueryResponse.RequestStatsResult,
            QueryResponse.ReplayStatsResult,
            QueryResponse.ReplaySingleResult,
            QueryResponse.ReplayBulkResult,
            QueryResponse.CertsPage,
            QueryResponse.CertDetail,
            QueryResponse.CertsList,
            QueryResponse.CertStatsResult,
            QueryResponse.CertGroupsPage,
            QueryResponse.CertGroupDetail,
            QueryResponse.CertGroupMembersList,
            QueryResponse.GatewayStatus,
            QueryResponse.CertRegistrySnapshot {

    // ═══════════════════════════════════════════════════════════════════════════
    // routify-route-service
    // ═══════════════════════════════════════════════════════════════════════════

    /** Full active-route + filter snapshot for the gateway. */
    record GatewaySnapshotList(List<RouteSnapshot> routes) implements QueryResponse {

        /** Single route snapshot as consumed by routify-api-gateway. */
        public record RouteSnapshot(
                UUID routeId,
                UUID tenantId,
                String name,
                String pathPattern,
                String methods,
                String upstreamUri,
                String stripPrefix,
                Integer version,
                List<FilterSnapshot> filters,
                Map<String, Object> extraConfig
        ) {
            public record FilterSnapshot(
                    UUID filterId,
                    String filterType,
                    int order,
                    String phase,
                    Map<String, Object> config,
                    Map<String, Object> gatewayConfigRef
            ) {}
        }
    }

    /** Gateway global configuration key-value map. */
    record GatewayConfig(Map<String, Object> config) implements QueryResponse {}

    /** Route count statistics, optionally scoped to a tenant. */
    record RouteStatsResult(
            long total,
            long active,
            long inactive,
            long draft,
            UUID tenantId
    ) implements QueryResponse {}

    /** Paginated list of route summaries. */
    record RoutesPage(
            List<RouteSummary> content,
            long totalElements,
            int totalPages,
            int page,
            int size
    ) implements QueryResponse {

        public record RouteSummary(
                UUID id,
                String name,
                String description,
                String pathPattern,
                String methods,
                String upstreamUri,
                RouteStatus status,
                Integer version,
                int filterCount,
                Instant createdAt,
                Instant activatedAt
        ) {}
    }

    /** Full detail of a single route including attached filters. */
    record RouteDetail(
            UUID id,
            UUID tenantId,
            String name,
            String description,
            String pathPattern,
            String methods,
            String upstreamUri,
            String stripPrefix,
            RouteStatus status,
            Integer version,
            List<FilterRef> filters,
            Map<String, Object> extraConfig,
            String createdBy,
            Instant createdAt,
            Instant updatedAt,
            Instant activatedAt
    ) implements QueryResponse {

        public record FilterRef(
                UUID filterId,
                String filterName,
                String filterType,
                int order,
                String phase,
                boolean enabled
        ) {}
    }

    /** Paginated list of filter-definition summaries. */
    record FiltersPage(
            List<FilterSummary> content,
            long totalElements,
            int totalPages,
            int page,
            int size
    ) implements QueryResponse {

        public record FilterSummary(
                UUID id,
                String name,
                FilterType filterType,
                Boolean enabled,
                Integer usageCount,
                GatewayConfigRef gatewayConfigRef,
                Instant createdAt
        ) {}
    }

    /** Full detail of a single filter definition. */
    record FilterDetail(
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
    ) implements QueryResponse {}

    /**
     * Reference to a gateway configuration entry (auth provider, rate limit policy, etc.)
     * Shared across route and filter response types.
     */
    record GatewayConfigRef(
            String refType,
            String refId,
            String refName
    ) {}

    // ═══════════════════════════════════════════════════════════════════════════
    // routify-identity-service
    // ═══════════════════════════════════════════════════════════════════════════

    /** Successful authentication response. */
    record LoginResult(
            String accessToken,
            String refreshToken,
            String tokenType,
            long expiresIn,
            boolean mustChangePassword,
            UserInfo user
    ) implements QueryResponse {

        public record UserInfo(
                UUID id,
                UUID tenantId,
                String username,
                String email,
                UserRole role,
                boolean mustChangePassword
        ) {}
    }

    /** Result of a password change or reset operation. */
    record PasswordChangeResult(boolean success) implements QueryResponse {}

    /** Paginated list of users. */
    record UsersPage(
            List<UserSummary> content,
            long totalElements,
            int totalPages,
            int page,
            int size
    ) implements QueryResponse {

        public record UserSummary(
                UUID id,
                UUID tenantId,
                String username,
                String email,
                UserRole role,
                String status,
                boolean mustChangePassword,
                Instant lastLoginAt,
                Instant createdAt
        ) {}
    }

    /** Full detail of a single user. */
    record UserDetail(
            UUID id,
            UUID tenantId,
            String username,
            String email,
            UserRole role,
            String status,
            boolean mustChangePassword,
            Instant lastLoginAt,
            Instant createdAt
    ) implements QueryResponse {}

    /** Paginated list of tenants. */
    record TenantsPage(
            List<TenantSummary> content,
            long totalElements,
            int totalPages,
            int page,
            int size
    ) implements QueryResponse {

        public record TenantSummary(
                UUID id,
                String name,
                String slug,
                String status,
                TenantPlan plan,
                String contactEmail,
                Instant createdAt
        ) {}
    }

    /** Full detail of a single tenant. */
    record TenantDetail(
            UUID id,
            String name,
            String slug,
            String status,
            TenantPlan plan,
            String contactEmail,
            Instant createdAt
    ) implements QueryResponse {}

    /** Active workspace names + slugs for the login-page dropdown. */
    record ActiveWorkspacesList(List<WorkspaceInfo> workspaces) implements QueryResponse {

        public record WorkspaceInfo(String name, String slug) {}
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // routify-audit-service
    // ═══════════════════════════════════════════════════════════════════════════

    /** Paginated audit event log. */
    record AuditEventsPage(
            List<AuditEventEntry> content,
            long totalElements,
            int totalPages,
            int page,
            int size
    ) implements QueryResponse {

        public record AuditEventEntry(
                UUID eventId,
                UUID tenantId,
                String eventType,
                String aggregateType,
                String aggregateId,
                String actorId,
                String correlationId,
                Instant occurredAt,
                Instant recordedAt
        ) {}
    }

    /** Paginated request log. */
    record RequestLogsPage(
            List<RequestLogEntry> content,
            long totalElements,
            int totalPages,
            int page,
            int size
    ) implements QueryResponse {

        public record RequestLogEntry(
                UUID id,
                UUID tenantId,
                UUID routeId,
                String routeName,
                String correlationId,
                String method,
                String path,
                String queryString,
                String upstreamUri,
                Integer responseStatus,
                Long durationMs,
                String clientIp,
                String userId,
                String errorMessage,
                boolean failed,
                String requestHeaders,
                String responseHeaders,
                String requestBody,
                String responseBody,
                String replayStatus,
                int replayCount,
                Instant replayedAt,
                Integer replayResponseStatus,
                String replayError,
                Instant requestedAt
        ) {}
    }

    /** Per-route HTTP request statistics. */
    record RequestStatsResult(
            UUID routeId,
            long totalRequests,
            double avgDurationMs,
            long maxDurationMs,
            long errorCount
    ) implements QueryResponse {}

    /** Replay status counts (pending / in-progress / succeeded / failed / skipped). */
    record ReplayStatsResult(
            long pending,
            long inProgress,
            long succeeded,
            long failed,
            long skipped
    ) implements QueryResponse {}

    /** Outcome of replaying a single failed request. */
    record ReplaySingleResult(
            UUID requestLogId,
            String outcome,
            Integer responseStatus,
            String message
    ) implements QueryResponse {}

    /** Outcome of a bulk replay run. */
    record ReplayBulkResult(
            int total,
            int succeeded,
            int failed,
            int skipped
    ) implements QueryResponse {}

    // ═══════════════════════════════════════════════════════════════════════════
    // routify-cert-vault
    // ═══════════════════════════════════════════════════════════════════════════

    /** Paginated list of certificate metadata. */
    record CertsPage(
            List<CertSummary> content,
            long totalElements,
            int totalPages,
            int page,
            int size,
            boolean first,
            boolean last
    ) implements QueryResponse {}

    /** Full certificate metadata (no raw PEM material). */
    record CertDetail(
            UUID id,
            UUID tenantId,
            String logicalId,
            String alias,
            String description,
            String format,
            String status,
            String expiryStatus,
            String subjectDn,
            String issuerDn,
            String serialNumber,
            Instant notBefore,
            Instant notAfter,
            String signatureAlg,
            String keyAlgorithm,
            Integer keySize,
            String fingerprintSha1,
            String fingerprintSha256,
            List<String> sanDns,
            List<String> sanIp,
            boolean isCa,
            boolean hasPrivateKey,
            String gatewayTlsLogicalId,
            UUID groupId,
            String groupLogicalId,
            String memberAlias,
            String effectiveGatewayLogicalId,
            String uploadedBy,
            Instant createdAt,
            Instant updatedAt,
            Instant expiresAt
    ) implements QueryResponse {}

    /**
     * Summary fields shared between list pages and group member lists.
     * Not a sealed type itself — used as a nested record within paginated responses.
     */
    record CertSummary(
            UUID id,
            UUID tenantId,
            String logicalId,
            String alias,
            String description,
            String format,
            String status,
            String expiryStatus,
            String subjectDn,
            String issuerDn,
            String serialNumber,
            Instant notBefore,
            Instant notAfter,
            String signatureAlg,
            String keyAlgorithm,
            Integer keySize,
            String fingerprintSha1,
            String fingerprintSha256,
            List<String> sanDns,
            List<String> sanIp,
            boolean isCa,
            boolean hasPrivateKey,
            String gatewayTlsLogicalId,
            UUID groupId,
            String groupLogicalId,
            String memberAlias,
            String effectiveGatewayLogicalId,
            String uploadedBy,
            Instant createdAt,
            Instant updatedAt,
            Instant expiresAt
    ) {}

    /** List of active or gateway-mapped certificates (no pagination). */
    record CertsList(List<CertSummary> items) implements QueryResponse {}

    /** Vault statistics — counts by certificate status. */
    record CertStatsResult(
            long total,
            long active,
            long expiringSoon,
            Map<String, Long> counts
    ) implements QueryResponse {}

    /** Paginated list of cert-group summaries. */
    record CertGroupsPage(
            List<CertGroupSummary> content,
            long totalElements,
            int totalPages,
            int page,
            int size,
            boolean first,
            boolean last
    ) implements QueryResponse {}

    /** Full cert-group detail including member list. */
    record CertGroupDetail(
            UUID id,
            UUID tenantId,
            String logicalId,
            String alias,
            String description,
            String status,
            int memberCount,
            String expiryHealthStatus,
            List<CertSummary> members,
            String createdBy,
            Instant createdAt,
            Instant updatedAt
    ) implements QueryResponse {}

    /** Summary fields for a cert group in list views. */
    record CertGroupSummary(
            UUID id,
            UUID tenantId,
            String logicalId,
            String alias,
            String description,
            String status,
            int memberCount,
            String expiryHealthStatus,
            String createdBy,
            Instant createdAt,
            Instant updatedAt
    ) {}

    /** List of certificate members of a group. */
    record CertGroupMembersList(List<CertSummary> members) implements QueryResponse {}

    // ═══════════════════════════════════════════════════════════════════════════
    // routify-api-gateway
    // ═══════════════════════════════════════════════════════════════════════════

    /** Live gateway status snapshot: route count, applied config, uptime. */
    record GatewayStatus(
            String status,
            int routeCount,
            String startTime,
            Map<String, Object> config,
            String timestamp
    ) implements QueryResponse {}

    /** Snapshot of all registered certificates in the in-memory CertificateRegistry. */
    record CertRegistrySnapshot(Map<String, CertRegistryEntry> entries) implements QueryResponse {

        public record CertRegistryEntry(
                String fingerprint,
                String notAfter,
                String source,
                String status,
                int version
        ) {}
    }
}

