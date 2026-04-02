package gr.routify.common.event;

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
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
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
            QueryRequest.CertGroupMembers {

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

    /** Paginated route list with optional status filter. */
    record RoutesQuery(
            UUID   tenantId,
            String status,
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
}

