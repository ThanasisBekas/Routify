package io.routify.common.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.routify.common.domain.FilterType;
import io.routify.common.domain.RouteEnvironment;
import io.routify.common.domain.RouteStatus;
import io.routify.common.domain.TenantPlan;
import io.routify.common.domain.UserRole;

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
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", defaultImpl = QueryResponse.Unknown.class)
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
    // ─── routify-identity-service API keys ──────────────────────────────────────
    @JsonSubTypes.Type(value = QueryResponse.ApiKeysPage.class,       name = "API_KEYS_PAGE"),
    @JsonSubTypes.Type(value = QueryResponse.ApiKeyDetail.class,      name = "API_KEY_DETAIL"),
    @JsonSubTypes.Type(value = QueryResponse.ApiKeyCreated.class,     name = "API_KEY_CREATED"),
    // ─── routify-identity-service webhooks ─────────────────────────────────────
    @JsonSubTypes.Type(value = QueryResponse.WebhooksPage.class,        name = "WEBHOOKS_PAGE"),
    @JsonSubTypes.Type(value = QueryResponse.WebhookDetail.class,       name = "WEBHOOK_DETAIL"),
    @JsonSubTypes.Type(value = QueryResponse.WebhookDeliveriesPage.class, name = "WEBHOOK_DELIVERIES_PAGE"),
    @JsonSubTypes.Type(value = QueryResponse.WebhookTestResult.class,   name = "WEBHOOK_TEST_RESULT"),
    // ─── routify-identity-service roles ──────────────────────────────────────────
    @JsonSubTypes.Type(value = QueryResponse.RolesPage.class,           name = "ROLES_PAGE"),
    @JsonSubTypes.Type(value = QueryResponse.RoleDetail.class,          name = "ROLE_DETAIL"),
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
    // ─── routify-cert-vault ACME ───────────────────────────────────────────────
    @JsonSubTypes.Type(value = QueryResponse.AcmeAccountResult.class,    name = "ACME_ACCOUNT_RESULT"),
    @JsonSubTypes.Type(value = QueryResponse.AcmeOrderDetail.class,      name = "ACME_ORDER_DETAIL"),
    @JsonSubTypes.Type(value = QueryResponse.AcmeOrdersPage.class,       name = "ACME_ORDERS_PAGE"),
    // ─── routify-ai-service ───────────────────────────────────────────────────
    @JsonSubTypes.Type(value = QueryResponse.AiFilterVerdict.class,         name = "AI_FILTER_VERDICT"),
    @JsonSubTypes.Type(value = QueryResponse.AiModifierVerdict.class,       name = "AI_MODIFIER_VERDICT"),
    // ─── routify-audit-service AI filter ─────────────────────────────────────
    @JsonSubTypes.Type(value = QueryResponse.AiFilterStatsResult.class,     name = "AI_FILTER_STATS_RESULT"),
    @JsonSubTypes.Type(value = QueryResponse.AiFilterDecisionsPage.class,   name = "AI_FILTER_DECISIONS_PAGE"),
    // ─── routify-audit-service route health ─────────────────────────────────
    @JsonSubTypes.Type(value = QueryResponse.RouteHealthResponse.class,     name = "ROUTE_HEALTH_RESPONSE"),
    // ─── routify-route-service SLO ──────────────────────────────────────────
    @JsonSubTypes.Type(value = QueryResponse.RouteSloResult.class,          name = "ROUTE_SLO_RESULT"),
    // ─── routify-audit-service tenant usage ────────────────────────────────
    @JsonSubTypes.Type(value = QueryResponse.UsageCurrentResult.class,     name = "USAGE_CURRENT_RESULT"),
    @JsonSubTypes.Type(value = QueryResponse.UsageHistoryResult.class,     name = "USAGE_HISTORY_RESULT"),
    // ─── routify-audit-service AI prompt versions ────────────────────────
    @JsonSubTypes.Type(value = QueryResponse.PromptVersionsPage.class,     name = "PROMPT_VERSIONS_PAGE"),
    @JsonSubTypes.Type(value = QueryResponse.PromptVersionDetail.class,    name = "PROMPT_VERSION_DETAIL"),
    @JsonSubTypes.Type(value = QueryResponse.AiDecisionLabelResult.class,  name = "AI_DECISION_LABEL_RESULT"),
    // ─── routify-audit-service time-series (GraphQL Initiative 13) ──────────
    @JsonSubTypes.Type(value = QueryResponse.TimeSeriesResult.class,      name = "TIME_SERIES_RESULT"),
    // ─── routify-api-gateway ─────────────────────────────────────────────────
    @JsonSubTypes.Type(value = QueryResponse.GatewayStatus.class,        name = "GATEWAY_STATUS"),
    @JsonSubTypes.Type(value = QueryResponse.CertRegistrySnapshot.class, name = "CERT_REGISTRY_SNAPSHOT"),
    // ─── Canary routing (Initiative 14) ───────────────────────────────────────
    @JsonSubTypes.Type(value = QueryResponse.CanaryStatusResult.class,   name = "CANARY_STATUS_RESULT"),
    // ─── Alerting engine (Initiative 15) ────────────────────────────────────
    @JsonSubTypes.Type(value = QueryResponse.AlertRulesPage.class,       name = "ALERT_RULES_PAGE"),
    @JsonSubTypes.Type(value = QueryResponse.AlertRuleDetail.class,      name = "ALERT_RULE_DETAIL"),
    @JsonSubTypes.Type(value = QueryResponse.AlertEventsPage.class,      name = "ALERT_EVENTS_PAGE"),
    // ─── routify-identity-service internal (cache warmup) ──────────────────
    @JsonSubTypes.Type(value = QueryResponse.TenantPlansList.class,      name = "TENANT_PLANS_LIST"),
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
            QueryResponse.ApiKeysPage,
            QueryResponse.ApiKeyDetail,
            QueryResponse.ApiKeyCreated,
            QueryResponse.WebhooksPage,
            QueryResponse.WebhookDetail,
            QueryResponse.WebhookDeliveriesPage,
            QueryResponse.WebhookTestResult,
            QueryResponse.RolesPage,
            QueryResponse.RoleDetail,
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
            QueryResponse.AcmeAccountResult,
            QueryResponse.AcmeOrderDetail,
            QueryResponse.AcmeOrdersPage,
            QueryResponse.GatewayStatus,
            QueryResponse.CertRegistrySnapshot,
            QueryResponse.AiFilterVerdict,
            QueryResponse.AiModifierVerdict,
            QueryResponse.AiFilterStatsResult,
            QueryResponse.AiFilterDecisionsPage,
            QueryResponse.RouteHealthResponse,
            QueryResponse.RouteSloResult,
            QueryResponse.UsageCurrentResult,
            QueryResponse.UsageHistoryResult,
            QueryResponse.PromptVersionsPage,
            QueryResponse.PromptVersionDetail,
            QueryResponse.AiDecisionLabelResult,
            QueryResponse.TimeSeriesResult,
            QueryResponse.CanaryStatusResult,
            QueryResponse.AlertRulesPage,
            QueryResponse.AlertRuleDetail,
            QueryResponse.AlertEventsPage,
            QueryResponse.TenantPlansList,
            QueryResponse.Unknown {

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
                RouteEnvironment environment,
                Integer version,
                int filterCount,
                Instant createdAt,
                Instant activatedAt,
                int trafficWeight,
                UUID canaryRouteId
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
                boolean mustChangePassword,
                List<String> permissions
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
                Instant createdAt,
                UUID roleId,
                String roleName,
                List<String> permissions
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
            Instant createdAt,
            UUID roleId,
            String roleName,
            List<String> permissions
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

    // ─── API Key responses ──────────────────────────────────────────────────

    /** Paginated list of API key summaries. */
    record ApiKeysPage(
            List<ApiKeySummary> content,
            long totalElements,
            int totalPages,
            int page,
            int size
    ) implements QueryResponse {

        public record ApiKeySummary(
                UUID id,
                UUID tenantId,
                String name,
                String keyPrefix,
                String role,
                String email,
                String status,
                Instant expiresAt,
                Instant lastUsedAt,
                Instant createdAt
        ) {}
    }

    /** Full detail of a single API key (no raw key — only prefix). */
    record ApiKeyDetail(
            UUID id,
            UUID tenantId,
            UUID userId,
            String name,
            String keyPrefix,
            String role,
            String email,
            String status,
            Instant expiresAt,
            Instant lastUsedAt,
            UUID createdBy,
            Instant createdAt,
            Instant revokedAt
    ) implements QueryResponse {}

    /** Result of creating or rotating an API key — includes the raw key shown once. */
    record ApiKeyCreated(
            UUID id,
            String rawKey,
            String keyPrefix,
            String name,
            String role,
            Instant expiresAt,
            Instant createdAt
    ) implements QueryResponse {}

    // ─── Webhook responses ──────────────────────────────────────────────────

    /** Paginated list of webhook subscriptions. */
    record WebhooksPage(
            List<WebhookSummary> content,
            long totalElements,
            int totalPages,
            int page,
            int size
    ) implements QueryResponse {

        public record WebhookSummary(
                UUID id,
                UUID tenantId,
                String name,
                String url,
                List<String> eventTypes,
                String status,
                int failureCount,
                Instant lastDeliveredAt,
                Instant createdAt,
                Instant updatedAt
        ) {}
    }

    /** Full detail of a single webhook subscription. */
    record WebhookDetail(
            UUID id,
            UUID tenantId,
            String name,
            String url,
            String secret,
            List<String> eventTypes,
            String status,
            int failureCount,
            Instant lastDeliveredAt,
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt
    ) implements QueryResponse {}

    /** Paginated list of webhook delivery attempts. */
    record WebhookDeliveriesPage(
            List<DeliveryEntry> content,
            long totalElements,
            int totalPages,
            int page,
            int size
    ) implements QueryResponse {

        public record DeliveryEntry(
                UUID id,
                UUID subscriptionId,
                String eventType,
                String payload,
                Integer responseStatus,
                String responseBody,
                int attempt,
                String status,
                Instant deliveredAt,
                Instant nextRetryAt,
                String errorMessage,
                Instant createdAt
        ) {}
    }

    /** Result of a webhook test-ping. */
    record WebhookTestResult(
            boolean success,
            Integer responseStatus,
            String message
    ) implements QueryResponse {}

    // ─── Role responses ──────────────────────────────────────────────────────

    /** Paginated list of role definitions. */
    record RolesPage(
            List<RoleSummary> content,
            long totalElements,
            int totalPages,
            int page,
            int size
    ) implements QueryResponse {

        public record RoleSummary(
                UUID id,
                UUID tenantId,
                String name,
                String description,
                boolean builtIn,
                List<String> permissions,
                Instant createdAt
        ) {}
    }

    /** Full detail of a single role definition with permissions. */
    record RoleDetail(
            UUID id,
            UUID tenantId,
            String name,
            String description,
            boolean builtIn,
            List<String> permissions,
            Instant createdAt
    ) implements QueryResponse {}

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

    // ─── ACME responses ──────────────────────────────────────────────────────

    /** ACME account registration result. */
    record AcmeAccountResult(
            UUID   id,
            UUID   tenantId,
            String email,
            String accountUrl,
            String provider,
            String status,
            Instant createdAt
    ) implements QueryResponse {}

    /** ACME order detail. */
    record AcmeOrderDetail(
            UUID    id,
            UUID    tenantId,
            String  domain,
            UUID    certGroupId,
            String  challengeType,
            String  status,
            String  orderUrl,
            String  challengeToken,
            UUID    certId,
            boolean autoRenew,
            Instant lastRenewedAt,
            Instant nextRenewalAt,
            String  errorMessage,
            Instant createdAt,
            Instant updatedAt
    ) implements QueryResponse {}

    /** Paginated ACME orders list. */
    record AcmeOrdersPage(
            List<AcmeOrderDetail> content,
            long totalElements,
            int  totalPages,
            int  page,
            int  size,
            boolean first,
            boolean last
    ) implements QueryResponse {}

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

    // ═══════════════════════════════════════════════════════════════════════════
    // routify-ai-service
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * AI filter verdict returned by routify-ai-service to the gateway over RabbitMQ RPC.
     *
     * @param action        Enforcement decision — {@code ALLOW}, {@code BLOCK}, or {@code FLAG}.
     * @param reason        One-sentence LLM explanation (included in 403 body for BLOCK verdicts).
     * @param confidence    LLM confidence score in [0.0, 1.0]; 0.0 for fallback verdicts.
     * @param isAllowed     Convenience boolean: {@code true} when action is ALLOW or FLAG.
     * @param cached        {@code true} when this verdict was served from the Redis cache.
     * @param latencyMs     Total evaluation latency in milliseconds.
     * @param evaluationId  Unique trace ID for audit correlation.
     */
    record AiFilterVerdict(
            String  action,
            String  reason,
            double  confidence,
            boolean isAllowed,
            boolean cached,
            long    latencyMs,
            String  evaluationId
    ) implements QueryResponse {

        /** Parse-safe factory: if action is unrecognised, defaults to ALLOW. */
        public static AiFilterVerdict fallback(String fallbackAction, String reason, long latencyMs) {
            String safeAction = (fallbackAction != null) ? fallbackAction.toUpperCase() : "ALLOW";
            boolean allowed = !"BLOCK".equals(safeAction);
            return new AiFilterVerdict(safeAction, reason, 0.0, allowed, false, latencyMs, "fallback");
        }
    }

    // ─── routify-ai-service AI modifier ──────────────────────────────────────

    /**
     * AI modifier verdict returned by routify-ai-service after evaluating a mutation request.
     *
     * <p>When {@code mutationApplied=true} the gateway must replace the outgoing request
     * headers and/or body with the values from this verdict before forwarding downstream.
     * When {@code mutationApplied=false} the gateway passes the request through unchanged
     * (passthrough behaviour controlled by {@code fallbackBehavior}).
     *
     * @param mutationId      Unique trace ID — matches {@code X-AI-Modifier-Id} header.
     * @param mutationApplied Whether the LLM produced a valid mutation (false = passthrough).
     * @param mutationType    PII_SCRUB | TRANSLATE | HEADER_REWRITE | CUSTOM | PASSTHROUGH
     * @param mutatedHeaders  Headers to replace on the request (empty map = no header changes).
     * @param mutatedBody     Replacement request body (null = body unchanged).
     * @param reason          One-sentence explanation from the LLM.
     * @param cached          Whether the mutation result was served from Redis cache.
     * @param latencyMs       Total evaluation latency in milliseconds.
     */
    record AiModifierVerdict(
            String              mutationId,
            boolean             mutationApplied,
            String              mutationType,
            Map<String, String> mutatedHeaders,
            String              mutatedBody,
            String              reason,
            boolean             cached,
            long                latencyMs
    ) implements QueryResponse {

        /**
         * Passthrough fallback — used when the LLM is unavailable or the circuit breaker is open.
         * The gateway will forward the original request unchanged.
         */
        public static AiModifierVerdict passthrough(String reason, long latencyMs) {
            return new AiModifierVerdict(
                    "fallback", false, "PASSTHROUGH", Map.of(), null,
                    reason, false, latencyMs);
        }
    }

    // ─── routify-audit-service AI filter analytics ────────────────────────────

    /**
     * Aggregated AI filter statistics for a tenant/route over a time window.
     *
     * @param tenantId       Scoping tenant UUID.
     * @param routeId        Route UUID (null = aggregated across all routes).
     * @param totalDecisions Total evaluations in the window.
     * @param allowCount     Number of ALLOW verdicts.
     * @param blockCount     Number of BLOCK verdicts.
     * @param flagCount      Number of FLAG verdicts.
     * @param fallbackCount  Number of fallback (circuit-open / timeout) verdicts.
     * @param cacheHitCount  Number of Redis cache hits (LLM not called).
     * @param avgLatencyMs   Mean evaluation latency across all decisions.
     * @param p95LatencyMs   95th-percentile latency.
     * @param p99LatencyMs   99th-percentile latency.
     * @param from           Window start (ISO-8601).
     * @param to             Window end (ISO-8601).
     */
    record AiFilterStatsResult(
            UUID   tenantId,
            UUID   routeId,
            long   totalDecisions,
            long   allowCount,
            long   blockCount,
            long   flagCount,
            long   fallbackCount,
            long   cacheHitCount,
            double avgLatencyMs,
            long   p95LatencyMs,
            long   p99LatencyMs,
            String from,
            String to
    ) implements QueryResponse {}

    /**
     * Paginated AI filter decision log entries.
     *
     * @param content       Page of decision entries.
     * @param totalElements Total matching decisions in the window.
     * @param totalPages    Total number of pages.
     * @param page          Current zero-based page number.
     * @param size          Page size.
     */
    record AiFilterDecisionsPage(
            List<AiFilterDecisionEntry> content,
            long  totalElements,
            int   totalPages,
            int   page,
            int   size
    ) implements QueryResponse {

        /**
         * A single AI filter decision audit record.
         *
         * @param evaluationId  Unique trace ID.
         * @param routeId       Route that triggered the evaluation.
         * @param routeName     Human-readable route name.
         * @param tenantId      Owning tenant.
         * @param action        ALLOW | BLOCK | FLAG
         * @param reason        LLM explanation.
         * @param confidence    LLM confidence score.
         * @param cached        Whether served from cache.
         * @param evaluationMode SYNC | ASYNC
         * @param latencyMs     Evaluation latency.
         * @param method        HTTP method.
         * @param path          Request path.
         * @param clientIp      Client IP address.
         * @param evaluatedAt   Timestamp of evaluation.
         */
        public record AiFilterDecisionEntry(
                String  evaluationId,
                UUID    routeId,
                String  routeName,
                UUID    tenantId,
                String  action,
                String  reason,
                double  confidence,
                boolean cached,
                String  evaluationMode,
                long    latencyMs,
                String  method,
                String  path,
                String  clientIp,
                Instant evaluatedAt
        ) {}
    }

    // ─── routify-audit-service route health (Gateway Health Dashboard v2) ────

    /**
     * Per-route health stats for the Gateway Health Dashboard v2 heatmap.
     *
     * @param routes List of per-route health entries within the requested window.
     */
    record RouteHealthResponse(List<RouteHealthEntry> routes) implements QueryResponse {

        /**
         * Health statistics for a single route over a time window.
         *
         * @param routeId              Route UUID.
         * @param routeName            Human-readable route name.
         * @param totalRequests        Total request count in the window.
         * @param errorCount           Requests with HTTP status >= 400.
         * @param errorRate            errorCount / totalRequests (0.0–1.0).
         * @param p50LatencyMs         Median latency in ms.
         * @param p95LatencyMs         95th-percentile latency in ms.
         * @param p99LatencyMs         99th-percentile latency in ms.
         * @param avgLatencyMs         Mean latency in ms.
         * @param statusCodeDistribution HTTP status code → count map.
         */
        public record RouteHealthEntry(
                UUID   routeId,
                String routeName,
                long   totalRequests,
                long   errorCount,
                double errorRate,
                double p50LatencyMs,
                double p95LatencyMs,
                double p99LatencyMs,
                double avgLatencyMs,
                Map<Integer, Long> statusCodeDistribution
        ) {}
    }

    // ─── routify-route-service SLO (Gateway Health Dashboard v2) ─────────────

    /**
     * SLO configuration for a route (or absence thereof).
     *
     * @param routeId              Route UUID.
     * @param availabilityTarget   Target availability percentage (e.g. 99.9).
     * @param latencyP99TargetMs   Target p99 latency in ms.
     * @param evaluationWindowHours Window in hours for SLO evaluation.
     * @param found                Whether an SLO config exists for this route.
     */
    record RouteSloResult(
            UUID   routeId,
            double availabilityTarget,
            int    latencyP99TargetMs,
            int    evaluationWindowHours,
            boolean found
    ) implements QueryResponse {}

    // ─── routify-audit-service tenant usage ──────────────────────────────────

    /**
     * Current-period usage for a tenant: resource counts and monthly request count vs plan limits.
     */
    record UsageCurrentResult(
            UUID tenantId,
            String plan,
            QuotaDimension routes,
            QuotaDimension filters,
            QuotaDimension requests,
            String periodStart,
            String periodEnd
    ) implements QueryResponse {
        /** A single quota dimension: used / limit / percentage. */
        public record QuotaDimension(long used, long limit, int percentage) {}
    }

    /**
     * Daily usage history for a tenant over a number of days.
     */
    record UsageHistoryResult(
            UUID tenantId,
            List<DailyUsage> entries
    ) implements QueryResponse {
        /** A single day's snapshot. */
        public record DailyUsage(String date, int routeCount, int filterCount,
                                  long requestCount, long errorCount) {}
    }

    // ─── routify-audit-service AI prompt versions ──────────────────────────

    /** Paginated list of prompt version summaries for a filter. */
    record PromptVersionsPage(
            List<PromptVersionSummary> content,
            long totalElements,
            int  totalPages,
            int  page,
            int  size
    ) implements QueryResponse {

        /** Summary view of a prompt version. */
        public record PromptVersionSummary(
                UUID                id,
                UUID                filterId,
                int                 version,
                String              status,
                String              description,
                java.math.BigDecimal accuracyScore,
                int                 totalDecisions,
                Instant             createdAt,
                Instant             activatedAt
        ) {}
    }

    /** Full detail of a single prompt version. */
    record PromptVersionDetail(
            UUID                id,
            UUID                filterId,
            UUID                tenantId,
            int                 version,
            String              promptText,
            String              description,
            String              status,
            java.math.BigDecimal accuracyScore,
            int                 totalDecisions,
            int                 correctCount,
            String              createdBy,
            Instant             createdAt,
            Instant             activatedAt,
            Instant             archivedAt
    ) implements QueryResponse {}

    /** Result of labelling an AI filter decision. */
    record AiDecisionLabelResult(
            boolean             success,
            UUID                promptVersionId,
            java.math.BigDecimal newAccuracy
    ) implements QueryResponse {}

    // ─── routify-audit-service time-series analytics (GraphQL Initiative 13) ──

    /**
     * Time-bucketed request metrics result for the GraphQL Analytics API.
     *
     * @param buckets List of time buckets with aggregated metrics.
     */
    record TimeSeriesResult(List<TimeSeriesBucket> buckets) implements QueryResponse {

        /**
         * A single time bucket with aggregated request metrics.
         *
         * @param timestamp    Bucket start timestamp (ISO-8601).
         * @param routeId      Route UUID (null if aggregated across all routes).
         * @param routeName    Route name.
         * @param requestCount Total requests in this bucket.
         * @param errorCount   Requests with HTTP status >= 400.
         * @param errorRate    errorCount / requestCount.
         * @param avgLatencyMs Mean latency in ms.
         * @param p50LatencyMs 50th-percentile latency.
         * @param p95LatencyMs 95th-percentile latency.
         * @param p99LatencyMs 99th-percentile latency.
         * @param statusCodes  Status code distribution.
         */
        public record TimeSeriesBucket(
                String timestamp,
                UUID   routeId,
                String routeName,
                long   requestCount,
                long   errorCount,
                double errorRate,
                double avgLatencyMs,
                double p50LatencyMs,
                double p95LatencyMs,
                double p99LatencyMs,
                Map<Integer, Long> statusCodes
        ) {}
    }

    // ─── Canary Routing (Initiative 14) ─────────────────────────────────────

    /**
     * Canary status response — composed by admin-api from route-service and audit-service data.
     */
    record CanaryStatusResult(
            UUID   routeId,
            UUID   canaryRouteId,
            int    primaryWeight,
            int    canaryWeight,
            String canaryUpstreamUri,
            double autoRollbackThreshold,
            double primaryErrorRate,
            double canaryErrorRate,
            String deployedAt,
            int    breachCount
    ) implements QueryResponse {}

    // ─── Alerting Engine (Initiative 15) ─────────────────────────────────────

    /** Paginated list of alert rules. */
    record AlertRulesPage(
            List<AlertRuleSummary> content,
            long totalElements,
            int  totalPages,
            int  page,
            int  size
    ) implements QueryResponse {

        public record AlertRuleSummary(
                UUID   id,
                UUID   tenantId,
                String name,
                String description,
                String metric,
                UUID   routeId,
                String operator,
                java.math.BigDecimal threshold,
                int    windowMinutes,
                int    cooldownMinutes,
                String severity,
                boolean enabled,
                String currentState,
                Instant stateChangedAt,
                int     consecutiveBreaches,
                Instant lastEvaluatedAt,
                Instant lastFiredAt,
                Instant mutedUntil,
                Instant createdAt
        ) {}
    }

    /** Alert rule detail — same as summary with createdBy and updatedAt. */
    record AlertRuleDetail(
            UUID   id,
            UUID   tenantId,
            String name,
            String description,
            String metric,
            UUID   routeId,
            String operator,
            java.math.BigDecimal threshold,
            int    windowMinutes,
            int    cooldownMinutes,
            String severity,
            boolean enabled,
            String currentState,
            Instant stateChangedAt,
            int     consecutiveBreaches,
            Instant lastEvaluatedAt,
            Instant lastFiredAt,
            Instant mutedUntil,
            String  createdBy,
            Instant createdAt,
            Instant updatedAt
    ) implements QueryResponse {}

    /** Paginated list of alert events (state transition history). */
    record AlertEventsPage(
            List<AlertEventEntry> content,
            long totalElements,
            int  totalPages,
            int  page,
            int  size
    ) implements QueryResponse {

        public record AlertEventEntry(
                UUID   id,
                UUID   ruleId,
                UUID   tenantId,
                String transition,
                java.math.BigDecimal metricValue,
                java.math.BigDecimal threshold,
                String  message,
                Instant occurredAt
        ) {}
    }

    // ─── routify-identity-service internal (cache warmup) ────────────────────

    /**
     * Full list of active tenant → plan mappings.
     * Returned by identity-service in response to {@link QueryRequest.TenantPlansQuery},
     * used by route-service and api-gateway to warm the in-memory TenantPlanCache on startup.
     */
    record TenantPlansList(List<TenantPlanEntry> entries) implements QueryResponse {

        /** Lightweight mapping of a tenant ID to its subscription plan name. */
        public record TenantPlanEntry(UUID tenantId, String plan) {}
    }

    /**
     * Fallback subtype used when the {@code "type"} discriminator is absent or unrecognised.
     * Prevents {@link com.fasterxml.jackson.databind.exc.InvalidTypeIdException} from being
     * thrown during deserialisation.
     */
    record Unknown() implements QueryResponse {}
}

