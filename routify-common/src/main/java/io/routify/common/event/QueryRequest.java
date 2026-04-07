package io.routify.common.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.UUID;

/**
 * Sealed interface representing all synchronous read (query) requests sent over RabbitMQ.
 *
 * <p>Requests flow from routify-admin-api to the owning microservice via the
 * Direct Reply-To RPC pattern (see {@link RabbitTopology}). The receiving
 * {@code @RabbitListener} method deserialises the JSON body into the
 * appropriate concrete record type using Jackson's {@code "type"} discriminator.
 *
 * <h2>Wire format</h2>
 * Each record is serialised with a {@code "type"} field that matches the
 * routing-key constant in {@link RabbitTopology}, making the wire format
 * self-describing without a separate envelope.
 *
 * <h2>Handler usage</h2>
 * <pre>{@code
 * @RabbitListener(queues = RabbitTopology.QUEUE_ROUTES_QUERY)
 * public QueryResponse.RoutesPage handleRoutesQuery(QueryRequest.RoutesQuery req) {
 *     var page = routeService.findAll(req.tenantId(), req.status(), req.pageable());
 *     return new QueryResponse.RoutesPage(page.map(mapper::toSummary).toList(), ...);
 * }
 * }</pre>
 *
 * <h2>Producer usage</h2>
 * <pre>{@code
 * return rpc(RabbitTopology.RK_ROUTES_QUERY,
 *            new QueryRequest.RoutesQuery(tenantId, status, page, size, sortBy, sortDir),
 *            QueryResponse.RoutesPage.class);
 * }</pre>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", defaultImpl = QueryRequest.Unknown.class)
@JsonSubTypes({
    // ─── routify-route-service ────────────────────────────────────────────────
    @JsonSubTypes.Type(value = QueryRequest.GatewaySnapshot.class,   name = "GATEWAY_SNAPSHOT"),
    @JsonSubTypes.Type(value = QueryRequest.GatewayConfigGet.class,  name = "GATEWAY_CONFIG_GET"),
    @JsonSubTypes.Type(value = QueryRequest.GatewayConfigSave.class, name = "GATEWAY_CONFIG_SAVE"),
    @JsonSubTypes.Type(value = QueryRequest.RouteStats.class,        name = "ROUTE_STATS"),
    @JsonSubTypes.Type(value = QueryRequest.RoutesQuery.class,       name = "ROUTES_QUERY"),
    @JsonSubTypes.Type(value = QueryRequest.RouteGet.class,          name = "ROUTE_GET"),
    @JsonSubTypes.Type(value = QueryRequest.RouteClone.class,        name = "ROUTE_CLONE"),
    @JsonSubTypes.Type(value = QueryRequest.FiltersQuery.class,      name = "FILTERS_QUERY"),
    @JsonSubTypes.Type(value = QueryRequest.FilterGet.class,         name = "FILTER_GET"),
    // ─── routify-identity-service ─────────────────────────────────────────────
    @JsonSubTypes.Type(value = QueryRequest.AuthLogin.class,         name = "AUTH_LOGIN"),
    @JsonSubTypes.Type(value = QueryRequest.AuthRefresh.class,       name = "AUTH_REFRESH"),
    @JsonSubTypes.Type(value = QueryRequest.AuthChangePassword.class,name = "AUTH_CHANGE_PASSWORD"),
    @JsonSubTypes.Type(value = QueryRequest.AdminResetPassword.class,name = "ADMIN_RESET_PASSWORD"),
    @JsonSubTypes.Type(value = QueryRequest.UsersQuery.class,        name = "USERS_QUERY"),
    @JsonSubTypes.Type(value = QueryRequest.UserGet.class,           name = "USER_GET"),
    @JsonSubTypes.Type(value = QueryRequest.TenantsQuery.class,      name = "TENANTS_QUERY"),
    @JsonSubTypes.Type(value = QueryRequest.TenantGet.class,         name = "TENANT_GET"),
    @JsonSubTypes.Type(value = QueryRequest.ListActiveWorkspaces.class, name = "LIST_ACTIVE_WORKSPACES"),
    // ─── routify-identity-service API keys ──────────────────────────────────────
    @JsonSubTypes.Type(value = QueryRequest.ApiKeysQuery.class,   name = "APIKEYS_QUERY"),
    @JsonSubTypes.Type(value = QueryRequest.ApiKeyGet.class,      name = "APIKEY_GET"),
    @JsonSubTypes.Type(value = QueryRequest.ApiKeyCreate.class,   name = "APIKEY_CREATE"),
    @JsonSubTypes.Type(value = QueryRequest.ApiKeyRevoke.class,   name = "APIKEY_REVOKE"),
    @JsonSubTypes.Type(value = QueryRequest.ApiKeyRotate.class,   name = "APIKEY_ROTATE"),
    // ─── routify-identity-service webhooks ─────────────────────────────────────
    @JsonSubTypes.Type(value = QueryRequest.WebhooksQuery.class,     name = "WEBHOOKS_QUERY"),
    @JsonSubTypes.Type(value = QueryRequest.WebhookGet.class,        name = "WEBHOOK_GET"),
    @JsonSubTypes.Type(value = QueryRequest.WebhookDeliveries.class, name = "WEBHOOK_DELIVERIES"),
    @JsonSubTypes.Type(value = QueryRequest.WebhookTest.class,       name = "WEBHOOK_TEST"),
    // ─── routify-identity-service roles ──────────────────────────────────────────
    @JsonSubTypes.Type(value = QueryRequest.RolesQuery.class,        name = "ROLES_QUERY"),
    @JsonSubTypes.Type(value = QueryRequest.RoleGet.class,           name = "ROLE_GET"),
    // ─── routify-audit-service ────────────────────────────────────────────────
    @JsonSubTypes.Type(value = QueryRequest.AuditEventsQuery.class,  name = "AUDIT_EVENTS_QUERY"),
    @JsonSubTypes.Type(value = QueryRequest.AuditRequestsQuery.class,name = "AUDIT_REQUESTS_QUERY"),
    @JsonSubTypes.Type(value = QueryRequest.AuditRequestStats.class, name = "AUDIT_REQUEST_STATS"),
    @JsonSubTypes.Type(value = QueryRequest.ReplayFailedQuery.class, name = "REPLAY_FAILED_QUERY"),
    @JsonSubTypes.Type(value = QueryRequest.ReplayPendingQuery.class,name = "REPLAY_PENDING_QUERY"),
    @JsonSubTypes.Type(value = QueryRequest.ReplayStats.class,       name = "REPLAY_STATS"),
    @JsonSubTypes.Type(value = QueryRequest.ReplaySingle.class,      name = "REPLAY_SINGLE"),
    @JsonSubTypes.Type(value = QueryRequest.ReplayBulk.class,        name = "REPLAY_BULK"),
    // ─── routify-cert-vault ───────────────────────────────────────────────────
    @JsonSubTypes.Type(value = QueryRequest.CertsQuery.class,        name = "CERTS_QUERY"),
    @JsonSubTypes.Type(value = QueryRequest.CertGet.class,           name = "CERT_GET"),
    @JsonSubTypes.Type(value = QueryRequest.CertsActiveList.class,   name = "CERTS_ACTIVE_LIST"),
    @JsonSubTypes.Type(value = QueryRequest.CertStats.class,         name = "CERT_STATS"),
    @JsonSubTypes.Type(value = QueryRequest.CertsGatewaySnapshot.class, name = "CERTS_GATEWAY_SNAPSHOT"),
    @JsonSubTypes.Type(value = QueryRequest.CertFetchMaterial.class, name = "CERT_FETCH_MATERIAL"),
    @JsonSubTypes.Type(value = QueryRequest.CertGroupsQuery.class,   name = "CERT_GROUPS_QUERY"),
    @JsonSubTypes.Type(value = QueryRequest.CertGroupGet.class,      name = "CERT_GROUP_GET"),
    @JsonSubTypes.Type(value = QueryRequest.CertGroupMembers.class,  name = "CERT_GROUP_MEMBERS"),
    // ─── routify-cert-vault ACME ───────────────────────────────────────────────
    @JsonSubTypes.Type(value = QueryRequest.AcmeRegister.class,       name = "ACME_REGISTER"),
    @JsonSubTypes.Type(value = QueryRequest.AcmeIssue.class,          name = "ACME_ISSUE"),
    @JsonSubTypes.Type(value = QueryRequest.AcmeOrdersQuery.class,    name = "ACME_ORDERS_QUERY"),
    @JsonSubTypes.Type(value = QueryRequest.AcmeOrderGet.class,       name = "ACME_ORDER_GET"),
    @JsonSubTypes.Type(value = QueryRequest.AcmeRenew.class,          name = "ACME_RENEW"),
    // ─── routify-ai-service ───────────────────────────────────────────────────
    @JsonSubTypes.Type(value = QueryRequest.AiFilterEvaluate.class,      name = "AI_FILTER_EVALUATE"),
    @JsonSubTypes.Type(value = QueryRequest.AiModifierEvaluate.class,    name = "AI_MODIFIER_EVALUATE"),
    // ─── routify-audit-service AI filter stats ────────────────────────────────
    @JsonSubTypes.Type(value = QueryRequest.AiFilterStatsQuery.class,    name = "AI_FILTER_STATS_QUERY"),
    @JsonSubTypes.Type(value = QueryRequest.AiFilterDecisionsQuery.class,name = "AI_FILTER_DECISIONS_QUERY"),
    // ─── routify-audit-service route health ─────────────────────────────────
    @JsonSubTypes.Type(value = QueryRequest.RouteHealthQuery.class,      name = "ROUTE_HEALTH_QUERY"),
    // ─── routify-route-service SLO ──────────────────────────────────────────
    @JsonSubTypes.Type(value = QueryRequest.RouteSloGet.class,           name = "ROUTE_SLO_GET"),
    @JsonSubTypes.Type(value = QueryRequest.RouteSloSave.class,          name = "ROUTE_SLO_SAVE"),
    // ─── routify-audit-service tenant usage ────────────────────────────────
    @JsonSubTypes.Type(value = QueryRequest.UsageCurrent.class,          name = "USAGE_CURRENT"),
    @JsonSubTypes.Type(value = QueryRequest.UsageHistory.class,          name = "USAGE_HISTORY"),
    // ─── routify-audit-service AI prompt versions ────────────────────────
    @JsonSubTypes.Type(value = QueryRequest.PromptVersionsQuery.class,   name = "PROMPT_VERSIONS_QUERY"),
    @JsonSubTypes.Type(value = QueryRequest.PromptVersionGet.class,      name = "PROMPT_VERSION_GET"),
    @JsonSubTypes.Type(value = QueryRequest.PromptVersionSave.class,     name = "PROMPT_VERSION_SAVE"),
    @JsonSubTypes.Type(value = QueryRequest.AiDecisionLabel.class,       name = "AI_DECISION_LABEL"),
})
public sealed interface QueryRequest
        permits
            QueryRequest.GatewaySnapshot,
            QueryRequest.GatewayConfigGet,
            QueryRequest.GatewayConfigSave,
            QueryRequest.RouteStats,
            QueryRequest.RoutesQuery,
            QueryRequest.RouteGet,
            QueryRequest.RouteClone,
            QueryRequest.FiltersQuery,
            QueryRequest.FilterGet,
            QueryRequest.AuthLogin,
            QueryRequest.AuthRefresh,
            QueryRequest.AuthChangePassword,
            QueryRequest.AdminResetPassword,
            QueryRequest.UsersQuery,
            QueryRequest.UserGet,
            QueryRequest.TenantsQuery,
            QueryRequest.TenantGet,
            QueryRequest.ListActiveWorkspaces,
            QueryRequest.ApiKeysQuery,
            QueryRequest.ApiKeyGet,
            QueryRequest.ApiKeyCreate,
            QueryRequest.ApiKeyRevoke,
            QueryRequest.ApiKeyRotate,
            QueryRequest.WebhooksQuery,
            QueryRequest.WebhookGet,
            QueryRequest.WebhookDeliveries,
            QueryRequest.WebhookTest,
            QueryRequest.RolesQuery,
            QueryRequest.RoleGet,
            QueryRequest.AuditEventsQuery,
            QueryRequest.AuditRequestsQuery,
            QueryRequest.AuditRequestStats,
            QueryRequest.ReplayFailedQuery,
            QueryRequest.ReplayPendingQuery,
            QueryRequest.ReplayStats,
            QueryRequest.ReplaySingle,
            QueryRequest.ReplayBulk,
            QueryRequest.CertsQuery,
            QueryRequest.CertGet,
            QueryRequest.CertsActiveList,
            QueryRequest.CertStats,
            QueryRequest.CertsGatewaySnapshot,
            QueryRequest.CertFetchMaterial,
            QueryRequest.CertGroupsQuery,
            QueryRequest.CertGroupGet,
            QueryRequest.CertGroupMembers,
            QueryRequest.AcmeRegister,
            QueryRequest.AcmeIssue,
            QueryRequest.AcmeOrdersQuery,
            QueryRequest.AcmeOrderGet,
            QueryRequest.AcmeRenew,
            QueryRequest.AiFilterEvaluate,
            QueryRequest.AiModifierEvaluate,
            QueryRequest.AiFilterStatsQuery,
            QueryRequest.AiFilterDecisionsQuery,
            QueryRequest.RouteHealthQuery,
            QueryRequest.RouteSloGet,
            QueryRequest.RouteSloSave,
            QueryRequest.UsageCurrent,
            QueryRequest.UsageHistory,
            QueryRequest.PromptVersionsQuery,
            QueryRequest.PromptVersionGet,
            QueryRequest.PromptVersionSave,
            QueryRequest.AiDecisionLabel,
            QueryRequest.Unknown {

    // ─── routify-route-service ────────────────────────────────────────────────

    /** No payload — requests the full active-route + filter snapshot for the gateway. */
    record GatewaySnapshot() implements QueryRequest {}

    /** No payload — retrieves the current gateway global config. */
    record GatewayConfigGet() implements QueryRequest {}

    /** Saves (or merges) the gateway global config. The {@code section} is advisory. */
    record GatewayConfigSave(
            String section,
            String changedBy,
            java.util.Map<String, Object> config
    ) implements QueryRequest {}

    /** Route count statistics, optionally scoped to a tenant. */
    record RouteStats(UUID tenantId) implements QueryRequest {}

    /** Paginated route list with optional status and environment filters. */
    record RoutesQuery(
            UUID   tenantId,
            String status,
            String environment,
            int    page,
            int    size,
            String sortBy,
            String sortDir
    ) implements QueryRequest {}

    /** Fetch a single route by ID. */
    record RouteGet(UUID id, UUID tenantId) implements QueryRequest {}

    /** Clone a route — returns the new route immediately (sync RPC). */
    record RouteClone(UUID id, UUID tenantId, String requestedBy) implements QueryRequest {}

    /** Paginated filter-definition list. */
    record FiltersQuery(
            UUID   tenantId,
            int    page,
            int    size,
            String sortBy,
            String sortDir
    ) implements QueryRequest {}

    /** Fetch a single filter definition by ID. */
    record FilterGet(UUID id, UUID tenantId) implements QueryRequest {}

    // ─── routify-identity-service ─────────────────────────────────────────────

    /** Login with username/password + tenant slug. */
    record AuthLogin(String username, String password, String tenantSlug) implements QueryRequest {}

    /** Rotate access token using the supplied refresh token. */
    record AuthRefresh(String refreshToken) implements QueryRequest {}

    /** Self-service password change — requires the current password. */
    record AuthChangePassword(
            UUID   userId,
            String currentPassword,
            String newPassword
    ) implements QueryRequest {}

    /** Admin-initiated password reset for another user. */
    record AdminResetPassword(
            UUID   userId,
            UUID   tenantId,
            String newPassword
    ) implements QueryRequest {}

    /** Paginated user list, optionally scoped to a tenant. */
    record UsersQuery(UUID tenantId, int page, int size) implements QueryRequest {}

    /** Fetch a single user by ID. */
    record UserGet(UUID id, UUID tenantId) implements QueryRequest {}

    /** Paginated tenant list. */
    record TenantsQuery(int page, int size) implements QueryRequest {}

    /** Fetch a single tenant by ID. */
    record TenantGet(UUID id) implements QueryRequest {}

    /** Returns active workspace names + slugs for the login-page dropdown. */
    record ListActiveWorkspaces() implements QueryRequest {}

    // ─── API Key queries (routify-identity-service) ────────────────────────────

    /** Paginated API key list, scoped to a tenant. */
    record ApiKeysQuery(UUID tenantId, int page, int size) implements QueryRequest {}

    /** Fetch a single API key by ID. */
    record ApiKeyGet(UUID id, UUID tenantId) implements QueryRequest {}

    /** Create an API key — sync RPC because raw key must be returned once. */
    record ApiKeyCreate(
            UUID     tenantId,
            UUID     userId,
            String   name,
            String   role,
            String   email,
            String   expiresAt,
            String   actor
    ) implements QueryRequest {}

    /** Revoke an API key — sync RPC for immediate confirmation. */
    record ApiKeyRevoke(UUID id, UUID tenantId, String actor) implements QueryRequest {}

    /** Rotate an API key — sync RPC because new raw key must be returned. */
    record ApiKeyRotate(UUID id, UUID tenantId, String actor) implements QueryRequest {}

    // ─── Webhook queries (routify-identity-service) ────────────────────────────

    /** Paginated webhook subscription list, scoped to a tenant. */
    record WebhooksQuery(UUID tenantId, int page, int size) implements QueryRequest {}

    /** Fetch a single webhook subscription by ID. */
    record WebhookGet(UUID id, UUID tenantId) implements QueryRequest {}

    /** Paginated delivery log for a webhook subscription. */
    record WebhookDeliveries(UUID subscriptionId, UUID tenantId, int page, int size) implements QueryRequest {}

    /** Send a test ping to a webhook subscription — sync RPC. */
    record WebhookTest(UUID id, UUID tenantId) implements QueryRequest {}

    // ─── Role queries (routify-identity-service) ────────────────────────────────

    /** Paginated role list, scoped to a tenant (includes built-in roles). */
    record RolesQuery(UUID tenantId, int page, int size) implements QueryRequest {}

    /** Fetch a single role by ID. */
    record RoleGet(UUID id) implements QueryRequest {}

    // ─── routify-audit-service ────────────────────────────────────────────────

    /** Paginated audit event log query with optional filters. */
    record AuditEventsQuery(
            UUID   tenantId,
            String eventType,
            String aggregateType,
            String aggregateId,
            String from,
            String to,
            int    page,
            int    size
    ) implements QueryRequest {}

    /** Paginated request log query with optional time-range and route filter. */
    record AuditRequestsQuery(
            UUID   tenantId,
            UUID   routeId,
            String from,
            String to,
            int    page,
            int    size
    ) implements QueryRequest {}

    /** Per-route request stats (count, avg duration, error count) over the last 24 h. */
    record AuditRequestStats(UUID tenantId, UUID routeId) implements QueryRequest {}

    /** Paginated list of failed requests eligible for replay. */
    record ReplayFailedQuery(UUID tenantId, UUID routeId, int page, int size) implements QueryRequest {}

    /** Paginated list of requests currently queued for replay. */
    record ReplayPendingQuery(UUID tenantId, UUID routeId, int page, int size) implements QueryRequest {}

    /** Replay status counts (pending / in-progress / succeeded / failed / skipped). */
    record ReplayStats(UUID tenantId) implements QueryRequest {}

    /** Trigger replay of a single failed request — returns the replay outcome synchronously. */
    record ReplaySingle(UUID id, UUID tenantId) implements QueryRequest {}

    /** Trigger bulk replay up to {@code limit} failed requests. */
    record ReplayBulk(UUID tenantId, int limit) implements QueryRequest {}

    // ─── routify-cert-vault ───────────────────────────────────────────────────

    /** Paginated certificate list with optional status filter and sort. */
    record CertsQuery(
            UUID   tenantId,
            String status,
            int    page,
            int    size,
            String sortBy,
            String sortDir
    ) implements QueryRequest {}

    /** Fetch a single certificate by ID. */
    record CertGet(UUID id, UUID tenantId) implements QueryRequest {}

    /** List all active certificates for a tenant (for the gateway config picker). */
    record CertsActiveList(UUID tenantId) implements QueryRequest {}

    /** Vault statistics — counts by certificate status. */
    record CertStats(UUID tenantId) implements QueryRequest {}

    /** List certificates that have a gateway TLS logical-ID mapping. */
    record CertsGatewaySnapshot(UUID tenantId) implements QueryRequest {}

    /**
     * Fetch decrypted certificate material (PEM chain + optional private key).
     * <strong>Internal only</strong> — called by routify-api-gateway only.
     */
    record CertFetchMaterial(UUID id, UUID tenantId) implements QueryRequest {}

    /** Paginated cert-group list with optional status filter and sort. */
    record CertGroupsQuery(
            UUID   tenantId,
            String status,
            int    page,
            int    size,
            String sortBy,
            String sortDir
    ) implements QueryRequest {}

    /** Fetch a single cert-group by ID. */
    record CertGroupGet(UUID id, UUID tenantId) implements QueryRequest {}

    /** List all certificate members of a group. */
    record CertGroupMembers(UUID groupId, UUID tenantId) implements QueryRequest {}

    // ─── routify-cert-vault ACME ───────────────────────────────────────────────

    /** Register an ACME account with a CA provider. */
    record AcmeRegister(UUID tenantId, String email, String provider) implements QueryRequest {}

    /** Request a certificate for a domain via ACME HTTP-01 challenge. */
    record AcmeIssue(UUID tenantId, UUID accountId, String domain, UUID certGroupId) implements QueryRequest {}

    /** Paginated ACME order list. */
    record AcmeOrdersQuery(UUID tenantId, int page, int size) implements QueryRequest {}

    /** Fetch a single ACME order by ID. */
    record AcmeOrderGet(UUID id, UUID tenantId) implements QueryRequest {}

    /** Trigger manual renewal of an ACME certificate order. */
    record AcmeRenew(UUID orderId, UUID tenantId) implements QueryRequest {}

    // ─── routify-ai-service ───────────────────────────────────────────────────

    /**
     * AI filter evaluation request — sent by the gateway to routify-ai-service via RabbitMQ RPC.
     *
     * <p>Carries the full route request context and the operator-defined AI filter config.
     * The ai-service responds with a {@link QueryResponse.AiFilterVerdict}.
     *
     * @param routeId             UUID of the route being evaluated.
     * @param routeName           Human-readable route name (for prompt context).
     * @param tenantId            Owning tenant UUID.
     * @param policyDescription   Natural-language policy rule to enforce.
     * @param evaluationMode      {@code SYNC} or {@code ASYNC} — gateway always sends SYNC over RPC.
     * @param includeBody         Whether a body excerpt is present in this payload.
     * @param maxBodyBytes        Max bytes of body excerpt included.
     * @param fallbackAction      Verdict to return if the LLM/circuit is unavailable.
     * @param confidenceThreshold Minimum LLM confidence to honour the verdict.
     * @param cacheEnabled        Whether the ai-service should consult the Redis verdict cache.
     * @param cacheTtlSeconds     Cache TTL in seconds.
     * @param method              HTTP method of the intercepted request.
     * @param path                Request path.
     * @param queryString         Raw query string (may be null).
     * @param clientIp            Originating client IP.
     * @param headers             Sanitised headers (sensitive values stripped by gateway).
     * @param bodyExcerpt         Base64-encoded request body prefix (null when includeBody=false).
     * @param userId              Authenticated user ID (null for unauthenticated requests).
     * @param userRole            Authenticated user role (null for unauthenticated requests).
     * @param correlationId       X-Correlation-Id propagated from the original request.
     * @param promptVersionId     UUID of the selected prompt version (null if no A/B split).
     */
    record AiFilterEvaluate(
            String              routeId,
            String              routeName,
            String              tenantId,
            // ── AI filter config ──
            String              policyDescription,
            String              evaluationMode,
            boolean             includeBody,
            int                 maxBodyBytes,
            String              fallbackAction,
            double              confidenceThreshold,
            boolean             cacheEnabled,
            int                 cacheTtlSeconds,
            // ── Request context ──
            String              method,
            String              path,
            String              queryString,
            String              clientIp,
            java.util.Map<String, String> headers,
            String              bodyExcerpt,
            String              userId,
            String              userRole,
            String              correlationId,
            String              promptVersionId
    ) implements QueryRequest {
        /** Backward-compatible constructor without promptVersionId. */
        public AiFilterEvaluate(
                String routeId, String routeName, String tenantId,
                String policyDescription, String evaluationMode, boolean includeBody, int maxBodyBytes,
                String fallbackAction, double confidenceThreshold, boolean cacheEnabled, int cacheTtlSeconds,
                String method, String path, String queryString, String clientIp,
                java.util.Map<String, String> headers, String bodyExcerpt,
                String userId, String userRole, String correlationId) {
            this(routeId, routeName, tenantId, policyDescription, evaluationMode, includeBody, maxBodyBytes,
                    fallbackAction, confidenceThreshold, cacheEnabled, cacheTtlSeconds,
                    method, path, queryString, clientIp, headers, bodyExcerpt,
                    userId, userRole, correlationId, null);
        }
    }

    /**
     * AI modification request — sent by the gateway to routify-ai-service via RabbitMQ RPC.
     *
     * <p>Carries the full route request context and the operator-defined AI modifier config.
     * Unlike {@link AiFilterEvaluate}, this request carries the actual body bytes (base64-encoded)
     * so the LLM can mutate them. The ai-service responds with a
     * {@link QueryResponse.AiModifierVerdict} that includes the mutated headers and body.
     *
     * @param routeId             UUID of the route being evaluated.
     * @param routeName           Human-readable route name.
     * @param tenantId            Owning tenant UUID.
     * @param modificationPrompt  Natural-language instruction for the mutation
     *                            (e.g. "Scrub all email addresses from the JSON body").
     * @param targetFields        Comma-separated targets: BODY, HEADERS, or BODY,HEADERS.
     * @param modelId             Optional LLM model override (null = service default).
     * @param temperature         LLM temperature (0.0–1.0). Default 0.1 for slight creativity.
     * @param maxTokens           Maximum tokens in the LLM response. Default 1024.
     * @param fallbackBehavior    PASSTHROUGH (default) or BLOCK on LLM failure.
     * @param includeBody         Whether the body is included in this payload.
     * @param maxBodyBytes        Max bytes of body to include (default 2048).
     * @param cacheEnabled        Whether to use Redis mutation caching.
     * @param cacheTtlSeconds     Cache TTL in seconds.
     * @param timeoutMs           Hard RPC timeout in milliseconds.
     * @param method              HTTP method of the intercepted request.
     * @param path                Request path.
     * @param queryString         Raw query string (may be null).
     * @param clientIp            Originating client IP.
     * @param headers             Sanitised request headers.
     * @param bodyBase64          Base64-encoded request body (null when includeBody=false).
     * @param correlationId       X-Correlation-Id propagated from the original request.
     */
    record AiModifierEvaluate(
            String              routeId,
            String              routeName,
            String              tenantId,
            // ── AI modifier config ──
            String              modificationPrompt,
            String              targetFields,
            String              modelId,
            double              temperature,
            int                 maxTokens,
            String              fallbackBehavior,
            boolean             includeBody,
            int                 maxBodyBytes,
            boolean             cacheEnabled,
            int                 cacheTtlSeconds,
            int                 timeoutMs,
            // ── Request context ──
            String              method,
            String              path,
            String              queryString,
            String              clientIp,
            java.util.Map<String, String> headers,
            String              bodyBase64,
            String              correlationId
    ) implements QueryRequest {}

    // ─── routify-audit-service AI filter stats ────────────────────────────────

    /**
     * Aggregated AI filter statistics per route over a time window.
     *
     * @param tenantId  Tenant to scope the query (required).
     * @param routeId   Optional — if null returns stats aggregated across all routes for the tenant.
     * @param from      ISO-8601 start timestamp (inclusive). Null = last 24 hours.
     * @param to        ISO-8601 end timestamp (exclusive). Null = now.
     * @param promptVersionId Optional — if set, scopes stats to a single prompt version (for A/B comparison).
     */
    record AiFilterStatsQuery(
            UUID   tenantId,
            UUID   routeId,
            String from,
            String to,
            UUID   promptVersionId
    ) implements QueryRequest {
        /** Backward-compatible constructor without promptVersionId. */
        public AiFilterStatsQuery(UUID tenantId, UUID routeId, String from, String to) {
            this(tenantId, routeId, from, to, null);
        }
    }

    /**
     * Paginated AI filter decision log with optional filters.
     *
     * @param tenantId  Tenant to scope the query (required).
     * @param routeId   Optional route filter.
     * @param action    Optional action filter: ALLOW, BLOCK, or FLAG.
     * @param from      ISO-8601 start timestamp. Null = last 24 hours.
     * @param to        ISO-8601 end timestamp. Null = now.
     * @param page      Zero-based page number.
     * @param size      Page size (max 100).
     */
    record AiFilterDecisionsQuery(
            UUID   tenantId,
            UUID   routeId,
            String action,
            String from,
            String to,
            int    page,
            int    size
    ) implements QueryRequest {}

    // ─── routify-audit-service route health ─────────────────────────────────

    /**
     * Per-route health stats (latency percentiles, error rate, status code distribution)
     * over a configurable time window. Used by the Gateway Health Dashboard v2.
     *
     * @param tenantId Tenant to scope the query (required).
     * @param window   Time window: "1h", "24h", or "7d".
     */
    record RouteHealthQuery(UUID tenantId, String window) implements QueryRequest {}

    // ─── routify-route-service SLO ──────────────────────────────────────────

    /** Fetch the SLO configuration for a route. */
    record RouteSloGet(UUID routeId, UUID tenantId) implements QueryRequest {}

    /** Upsert SLO configuration for a route. */
    record RouteSloSave(
            UUID   routeId,
            UUID   tenantId,
            double availabilityTarget,
            int    latencyP99TargetMs,
            int    evaluationWindowHours
    ) implements QueryRequest {}

    // ─── routify-audit-service tenant usage ──────────────────────────────────

    /** Fetch current-period usage vs plan limits for a tenant. */
    record UsageCurrent(UUID tenantId) implements QueryRequest {}

    /** Fetch daily usage history for a tenant over the last N days. */
    record UsageHistory(UUID tenantId, int days) implements QueryRequest {}

    // ─── routify-audit-service AI prompt versions ──────────────────────────

    /** Paginated prompt version list for a filter. */
    record PromptVersionsQuery(UUID filterId, UUID tenantId, int page, int size) implements QueryRequest {}

    /** Fetch a single prompt version by ID. */
    record PromptVersionGet(UUID id, UUID tenantId) implements QueryRequest {}

    /**
     * Create, activate, or archive a prompt version.
     *
     * @param filterId    Filter to version.
     * @param tenantId    Owning tenant.
     * @param versionId   Version UUID (null for CREATE_DRAFT, required for ACTIVATE/ARCHIVE).
     * @param promptText  Prompt text (required for CREATE_DRAFT, ignored otherwise).
     * @param description Human-readable description (optional).
     * @param action      CREATE_DRAFT | ACTIVATE | ARCHIVE.
     * @param requestedBy Actor performing the action.
     */
    record PromptVersionSave(
            UUID   filterId,
            UUID   tenantId,
            UUID   versionId,
            String promptText,
            String description,
            String action,
            String requestedBy
    ) implements QueryRequest {}

    /**
     * Label an AI filter decision as correct/incorrect/unclear for ground-truth feedback.
     *
     * @param evaluationId Evaluation trace ID.
     * @param tenantId     Owning tenant.
     * @param label        CORRECT | INCORRECT | UNCLEAR.
     */
    record AiDecisionLabel(String evaluationId, UUID tenantId, String label) implements QueryRequest {}

    /**
     * Fallback subtype used when the {@code "type"} discriminator is absent or unrecognised.
     */
    record Unknown() implements QueryRequest {}
}

