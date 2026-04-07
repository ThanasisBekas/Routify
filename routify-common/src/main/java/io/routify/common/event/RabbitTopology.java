package io.routify.common.event;

/**
 * RabbitMQ exchange, queue, and routing-key constants for ALL synchronous
 * request-reply communication between Routify microservices.
 *
 * <h2>Design Principles</h2>
 * <ul>
 *   <li>All <em>internal</em> service-to-service sync calls go through RabbitMQ
 *       using the Direct Reply-To pattern (no dedicated reply queues needed).</li>
 *   <li>Each logical "service" owns a <strong>direct exchange</strong> named
 *       after it. Requesters send to that exchange with a specific routing key.</li>
 *   <li>The responding service declares and binds its handler queue and replies
 *       to the {@code replyTo} address set by Spring AMQP automatically.</li>
 *   <li>routify-admin-api is the <strong>sole</strong> backend for the dashboard.
 *       It communicates with all other services via Kafka (commands/async) and
 *       RabbitMQ (queries/sync). No direct HTTP calls between services.</li>
 * </ul>
 *
 * <h2>Exchange → Queue → Routing Key Layout</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  routify.route-service  (direct exchange)                       │
 * │    └─ routify.route-service.gateway-snapshot                    │
 * │         routing-key: route.gateway.snapshot                     │
 * │    └─ routify.route-service.gateway-config.get                  │
 * │         routing-key: gateway.config.get                         │
 * │    └─ routify.route-service.gateway-config.save                 │
 * │         routing-key: gateway.config.save                        │
 * │    └─ routify.route-service.route-stats                         │
 * │         routing-key: route.stats                                │
 * │    └─ routify.route-service.routes.query                        │
 * │         routing-key: routes.query                               │
 *    └─ routify.route-service.routes.get                          │
 *         routing-key: routes.get                                 │
 *    └─ routify.route-service.routes.clone                        │
 *         routing-key: routes.clone                               │
 *    └─ routify.route-service.filters.query                       │
 * │         routing-key: filters.query                              │
 * │    └─ routify.route-service.filters.get                         │
 * │         routing-key: filters.get                                │
 * ├─────────────────────────────────────────────────────────────────┤
 * │  routify.identity-service  (direct exchange)                    │
 * │    └─ routify.identity-service.users.query                      │
 * │         routing-key: users.query                                │
 * │    └─ routify.identity-service.users.get                        │
 * │         routing-key: users.get                                  │
 * │    └─ routify.identity-service.tenants.query                    │
 * │         routing-key: tenants.query                              │
 * │    └─ routify.identity-service.tenants.get                      │
 * │         routing-key: tenants.get                                │
 * │    └─ routify.identity-service.tenants.command                  │
 * │         routing-key: tenants.command                            │
 * ├─────────────────────────────────────────────────────────────────┤
 * │  routify.audit-service  (direct exchange)                       │
 * │    └─ routify.audit-service.events.query                        │
 * │         routing-key: audit.events.query                         │
 * │    └─ routify.audit-service.requests.query                      │
 * │         routing-key: audit.requests.query                       │
 * │    └─ routify.audit-service.requests.stats                      │
 * │         routing-key: audit.requests.stats                       │
 * │    └─ routify.audit-service.replay.failed.query                 │
 * │         routing-key: audit.replay.failed.query                  │
 * │    └─ routify.audit-service.replay.pending.query                │
 * │         routing-key: audit.replay.pending.query                 │
 * │    └─ routify.audit-service.replay.stats                        │
 * │         routing-key: audit.replay.stats                         │
 * │    └─ routify.audit-service.replay.single                       │
 * │         routing-key: audit.replay.single                        │
 * │    └─ routify.audit-service.replay.bulk                         │
 * │         routing-key: audit.replay.bulk                          │
 * ├─────────────────────────────────────────────────────────────────┤
 * │  routify.gateway  (direct exchange)                             │
 * │    └─ routify.gateway.status                                    │
 * │         routing-key: gateway.status.request                     │
 * ├─────────────────────────────────────────────────────────────────┤
 * │  routify.cert-vault  (direct exchange)                          │
 * │    └─ routify.cert-vault.certs.query                            │
 * │         routing-key: certs.query                                │
 * │    └─ routify.cert-vault.certs.get                              │
 * │         routing-key: certs.get                                  │
 * │    └─ routify.cert-vault.certs.active                           │
 * │         routing-key: certs.active                               │
 * │    └─ routify.cert-vault.certs.stats                            │
 * │         routing-key: certs.stats                                │
 * │    └─ routify.cert-vault.certs.gateway-snapshot                 │
 * │         routing-key: certs.gateway.snapshot                     │
 * │    └─ routify.cert-vault.certs.fetch-material  (internal only)  │
 * │         routing-key: certs.fetch-material                       │
 * │    └─ routify.cert-vault.cert-groups.query                      │
 * │         routing-key: cert-groups.query                          │
 * │    └─ routify.cert-vault.cert-groups.get                        │
 * │         routing-key: cert-groups.get                            │
 * │    └─ routify.cert-vault.cert-groups.members                    │
 * │         routing-key: cert-groups.members                        │
 * └─────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public final class RabbitTopology {

    private RabbitTopology() {}

    // ─── Exchanges ────────────────────────────────────────────────────────────

    /** Direct exchange owned by routify-route-service */
    public static final String EXCHANGE_ROUTE_SERVICE    = "routify.route-service";

    /** Direct exchange owned by routify-identity-service */
    public static final String EXCHANGE_IDENTITY_SERVICE = "routify.identity-service";

    /** Direct exchange owned by routify-audit-service */
    public static final String EXCHANGE_AUDIT_SERVICE    = "routify.audit-service";

    /** Direct exchange owned by routify-api-gateway */
    public static final String EXCHANGE_GATEWAY          = "routify.gateway";

    /** Direct exchange owned by routify-cert-vault */
    public static final String EXCHANGE_CERT_VAULT       = "routify.cert-vault";

    // ─── routify-route-service queues & routing keys ──────────────────────────

    /** Queue: route-service serves gateway snapshot requests */
    public static final String QUEUE_ROUTE_GATEWAY_SNAPSHOT     = "routify.route-service.gateway-snapshot";
    public static final String RK_ROUTE_GATEWAY_SNAPSHOT        = "route.gateway.snapshot";

    /** Queue: route-service serves gateway config GET requests */
    public static final String QUEUE_GATEWAY_CONFIG_GET         = "routify.route-service.gateway-config.get";
    public static final String RK_GATEWAY_CONFIG_GET            = "gateway.config.get";

    /** Queue: route-service serves gateway config SAVE (PUT) requests */
    public static final String QUEUE_GATEWAY_CONFIG_SAVE        = "routify.route-service.gateway-config.save";
    public static final String RK_GATEWAY_CONFIG_SAVE           = "gateway.config.save";

    /** Queue: route-service serves route stats requests */
    public static final String QUEUE_ROUTE_STATS                = "routify.route-service.route-stats";
    public static final String RK_ROUTE_STATS                   = "route.stats";

    /** Queue: route-service serves paginated route list queries from admin-api */
    public static final String QUEUE_ROUTES_QUERY               = "routify.route-service.routes.query";
    public static final String RK_ROUTES_QUERY                  = "routes.query";

    /** Queue: route-service serves single-route GET queries from admin-api */
    public static final String QUEUE_ROUTES_GET                 = "routify.route-service.routes.get";
    public static final String RK_ROUTES_GET                    = "routes.get";

    /** Queue: route-service handles route clone commands from admin-api (sync RPC) */
    public static final String QUEUE_ROUTES_CLONE               = "routify.route-service.routes.clone";
    public static final String RK_ROUTES_CLONE                  = "routes.clone";

    /** Queue: route-service serves paginated filter list queries from admin-api */
    public static final String QUEUE_FILTERS_QUERY              = "routify.route-service.filters.query";
    public static final String RK_FILTERS_QUERY                 = "filters.query";

    /** Queue: route-service serves single-filter GET queries from admin-api */
    public static final String QUEUE_FILTERS_GET                = "routify.route-service.filters.get";
    public static final String RK_FILTERS_GET                   = "filters.get";

    // ─── routify-identity-service queues & routing keys ──────────────────────

    /** Queue: identity-service serves paginated user list queries from admin-api */
    public static final String QUEUE_USERS_QUERY                = "routify.identity-service.users.query";
    public static final String RK_USERS_QUERY                   = "users.query";

    /** Queue: identity-service serves single-user GET queries from admin-api */
    public static final String QUEUE_USERS_GET                  = "routify.identity-service.users.get";
    public static final String RK_USERS_GET                     = "users.get";

    /** Queue: identity-service serves paginated tenant list queries from admin-api */
    public static final String QUEUE_TENANTS_QUERY              = "routify.identity-service.tenants.query";
    public static final String RK_TENANTS_QUERY                 = "tenants.query";

    /** Queue: identity-service serves single-tenant GET from admin-api */
    public static final String QUEUE_TENANTS_GET                = "routify.identity-service.tenants.get";
    public static final String RK_TENANTS_GET                   = "tenants.get";

    /** Queue: identity-service serves tenant commands (suspend/reactivate) from admin-api */
    public static final String QUEUE_TENANTS_COMMAND            = "routify.identity-service.tenants.command";
    public static final String RK_TENANTS_COMMAND               = "tenants.command";

    /** Queue: identity-service serves active-workspace list for the login dropdown */
    public static final String QUEUE_TENANTS_LIST_ACTIVE        = "routify.identity-service.tenants.list-active";
    public static final String RK_TENANTS_LIST_ACTIVE           = "tenants.list-active";

    /** Queue: identity-service handles auth login requests from admin-api */
    public static final String QUEUE_AUTH_LOGIN                 = "routify.identity-service.auth.login";
    public static final String RK_AUTH_LOGIN                    = "auth.login";

    /** Queue: identity-service handles auth token refresh requests from admin-api */
    public static final String QUEUE_AUTH_REFRESH               = "routify.identity-service.auth.refresh";
    public static final String RK_AUTH_REFRESH                  = "auth.refresh";

    /** Queue: identity-service handles self password-change requests from admin-api */
    public static final String QUEUE_AUTH_CHANGE_PASSWORD       = "routify.identity-service.auth.change-password";
    public static final String RK_AUTH_CHANGE_PASSWORD          = "auth.change-password";

    /** Queue: identity-service handles admin-initiated password reset requests from admin-api */
    public static final String QUEUE_USERS_CHANGE_PASSWORD      = "routify.identity-service.users.change-password";
    public static final String RK_USERS_CHANGE_PASSWORD         = "users.change-password";

    // ─── routify-identity-service API key queues & routing keys ────────────────

    /** Queue: identity-service serves paginated API key list queries from admin-api */
    public static final String QUEUE_APIKEYS_QUERY              = "routify.identity-service.apikeys.query";
    public static final String RK_APIKEYS_QUERY                 = "apikeys.query";

    /** Queue: identity-service serves single API key GET queries from admin-api */
    public static final String QUEUE_APIKEYS_GET                = "routify.identity-service.apikeys.get";
    public static final String RK_APIKEYS_GET                   = "apikeys.get";

    /** Queue: identity-service handles API key create (sync RPC — raw key must be returned) */
    public static final String QUEUE_APIKEYS_CREATE             = "routify.identity-service.apikeys.create";
    public static final String RK_APIKEYS_CREATE                = "apikeys.create";

    /** Queue: identity-service handles API key revoke (sync RPC — immediate confirmation) */
    public static final String QUEUE_APIKEYS_REVOKE             = "routify.identity-service.apikeys.revoke";
    public static final String RK_APIKEYS_REVOKE                = "apikeys.revoke";

    /** Queue: identity-service handles API key rotate (sync RPC — new raw key must be returned) */
    public static final String QUEUE_APIKEYS_ROTATE             = "routify.identity-service.apikeys.rotate";
    public static final String RK_APIKEYS_ROTATE                = "apikeys.rotate";

    // ─── routify-identity-service webhook queues & routing keys ───────────────

    // ─── routify-identity-service role queues & routing keys ────────────────────

    /** Queue: identity-service serves paginated role list queries from admin-api */
    public static final String QUEUE_ROLES_QUERY              = "routify.identity-service.roles.query";
    public static final String RK_ROLES_QUERY                 = "roles.query";

    /** Queue: identity-service serves single role GET queries from admin-api */
    public static final String QUEUE_ROLES_GET                = "routify.identity-service.roles.get";
    public static final String RK_ROLES_GET                   = "roles.get";

    /** Queue: identity-service handles role commands (create/update/delete) from admin-api (sync RPC) */
    public static final String QUEUE_ROLES_COMMAND            = "routify.identity-service.roles.command";
    public static final String RK_ROLES_COMMAND               = "roles.command";

    // ─── routify-identity-service webhook queues & routing keys (continued) ────

    /** Queue: identity-service serves paginated webhook subscription list queries from admin-api */
    public static final String QUEUE_WEBHOOKS_QUERY              = "routify.identity-service.webhooks.query";
    public static final String RK_WEBHOOKS_QUERY                 = "webhooks.query";

    /** Queue: identity-service serves single webhook subscription GET queries from admin-api */
    public static final String QUEUE_WEBHOOKS_GET                = "routify.identity-service.webhooks.get";
    public static final String RK_WEBHOOKS_GET                   = "webhooks.get";

    /** Queue: identity-service serves paginated webhook delivery log queries from admin-api */
    public static final String QUEUE_WEBHOOKS_DELIVERIES         = "routify.identity-service.webhooks.deliveries";
    public static final String RK_WEBHOOKS_DELIVERIES            = "webhooks.deliveries";

    /** Queue: identity-service handles webhook test-ping requests from admin-api (sync RPC) */
    public static final String QUEUE_WEBHOOKS_TEST               = "routify.identity-service.webhooks.test";
    public static final String RK_WEBHOOKS_TEST                  = "webhooks.test";

    // ─── routify-audit-service queues & routing keys ─────────────────────────

    /** Queue: audit-service serves paginated audit event queries from admin-api */
    public static final String QUEUE_AUDIT_EVENTS_QUERY         = "routify.audit-service.events.query";
    public static final String RK_AUDIT_EVENTS_QUERY            = "audit.events.query";

    /** Queue: audit-service serves paginated request log queries from admin-api */
    public static final String QUEUE_AUDIT_REQUESTS_QUERY       = "routify.audit-service.requests.query";
    public static final String RK_AUDIT_REQUESTS_QUERY          = "audit.requests.query";

    /** Queue: audit-service serves per-route request stats queries from admin-api */
    public static final String QUEUE_AUDIT_REQUESTS_STATS       = "routify.audit-service.requests.stats";
    public static final String RK_AUDIT_REQUESTS_STATS          = "audit.requests.stats";

    /** Queue: audit-service serves failed-replay list queries from admin-api */
    public static final String QUEUE_AUDIT_REPLAY_FAILED_QUERY  = "routify.audit-service.replay.failed.query";
    public static final String RK_AUDIT_REPLAY_FAILED_QUERY     = "audit.replay.failed.query";

    /** Queue: audit-service serves pending-replay list queries from admin-api */
    public static final String QUEUE_AUDIT_REPLAY_PENDING_QUERY = "routify.audit-service.replay.pending.query";
    public static final String RK_AUDIT_REPLAY_PENDING_QUERY    = "audit.replay.pending.query";

    /** Queue: audit-service serves replay stats queries from admin-api */
    public static final String QUEUE_AUDIT_REPLAY_STATS         = "routify.audit-service.replay.stats";
    public static final String RK_AUDIT_REPLAY_STATS            = "audit.replay.stats";

    /** Queue: audit-service handles single-request replay commands from admin-api */
    public static final String QUEUE_AUDIT_REPLAY_SINGLE        = "routify.audit-service.replay.single";
    public static final String RK_AUDIT_REPLAY_SINGLE           = "audit.replay.single";

    /** Queue: audit-service handles bulk replay commands from admin-api */
    public static final String QUEUE_AUDIT_REPLAY_BULK          = "routify.audit-service.replay.bulk";
    public static final String RK_AUDIT_REPLAY_BULK             = "audit.replay.bulk";

    // ─── routify-api-gateway queues & routing keys ────────────────────────────

    /** Queue: gateway serves live status requests (health + routes + CBs) */
    public static final String QUEUE_GATEWAY_STATUS             = "routify.gateway.status";
    public static final String RK_GATEWAY_STATUS_REQUEST        = "gateway.status.request";

    /**
     * Queue: gateway serves a snapshot of its live in-memory CertificateRegistry.
     * Response is a JSON object: {@code { "<logicalId>": { "fingerprint", "notAfter", "source", "status", "version" } }}
     */
    public static final String QUEUE_GATEWAY_CERT_REGISTRY      = "routify.gateway.cert-registry";
    public static final String RK_GATEWAY_CERT_REGISTRY         = "gateway.cert.registry";

    // ─── routify-cert-vault queues & routing keys ─────────────────────────────

    /** Queue: cert-vault serves paginated certificate list queries from admin-api */
    public static final String QUEUE_CERTS_QUERY                = "routify.cert-vault.certs.query";
    public static final String RK_CERTS_QUERY                   = "certs.query";

    /** Queue: cert-vault serves single-cert GET queries from admin-api */
    public static final String QUEUE_CERTS_GET                  = "routify.cert-vault.certs.get";
    public static final String RK_CERTS_GET                     = "certs.get";

    /** Queue: cert-vault serves active certificates list (for gateway TLS picker) */
    public static final String QUEUE_CERTS_ACTIVE_LIST          = "routify.cert-vault.certs.active";
    public static final String RK_CERTS_ACTIVE_LIST             = "certs.active";

    /** Queue: cert-vault serves vault statistics queries from admin-api */
    public static final String QUEUE_CERTS_STATS                = "routify.cert-vault.certs.stats";
    public static final String RK_CERTS_STATS                   = "certs.stats";

    /** Queue: cert-vault serves gateway TLS snapshot (gateway-mapped certs) */
    public static final String QUEUE_CERTS_GATEWAY_SNAPSHOT     = "routify.cert-vault.certs.gateway-snapshot";
    public static final String RK_CERTS_GATEWAY_SNAPSHOT        = "certs.gateway.snapshot";

    /**
     * Queue: cert-vault serves decrypted certificate material (PEM chain + optional private key)
     * to routify-api-gateway for loading into the in-memory CertificateRegistry.
     * <strong>Internal only</strong> — never exposed outside the service mesh.
     */
    public static final String QUEUE_CERTS_FETCH_MATERIAL       = "routify.cert-vault.certs.fetch-material";
    public static final String RK_CERTS_FETCH_MATERIAL          = "certs.fetch-material";

    // ─── routify-cert-vault cert-group queues & routing keys ─────────────────

    /** Queue: cert-vault serves paginated cert-group list queries from admin-api */
    public static final String QUEUE_CERT_GROUPS_QUERY          = "routify.cert-vault.cert-groups.query";
    public static final String RK_CERT_GROUPS_QUERY             = "cert-groups.query";

    /** Queue: cert-vault serves single cert-group GET queries from admin-api */
    public static final String QUEUE_CERT_GROUPS_GET            = "routify.cert-vault.cert-groups.get";
    public static final String RK_CERT_GROUPS_GET               = "cert-groups.get";

    /** Queue: cert-vault serves cert-group members list queries from admin-api */
    public static final String QUEUE_CERT_GROUPS_MEMBERS        = "routify.cert-vault.cert-groups.members";
    public static final String RK_CERT_GROUPS_MEMBERS           = "cert-groups.members";

    // ─── routify-cert-vault ACME queues & routing keys ────────────────────────

    /** Queue: cert-vault handles ACME account registration (sync RPC) */
    public static final String QUEUE_ACME_REGISTER              = "routify.cert-vault.acme.register";
    public static final String RK_ACME_REGISTER                 = "acme.register";

    /** Queue: cert-vault handles ACME certificate issuance (sync RPC) */
    public static final String QUEUE_ACME_ISSUE                 = "routify.cert-vault.acme.issue";
    public static final String RK_ACME_ISSUE                    = "acme.issue";

    /** Queue: cert-vault serves paginated ACME order list queries from admin-api */
    public static final String QUEUE_ACME_ORDERS_QUERY          = "routify.cert-vault.acme.orders.query";
    public static final String RK_ACME_ORDERS_QUERY             = "acme.orders.query";

    /** Queue: cert-vault serves single ACME order GET queries from admin-api */
    public static final String QUEUE_ACME_ORDER_GET             = "routify.cert-vault.acme.orders.get";
    public static final String RK_ACME_ORDER_GET                = "acme.orders.get";

    /** Queue: cert-vault handles ACME certificate renewal (sync RPC) */
    public static final String QUEUE_ACME_RENEW                 = "routify.cert-vault.acme.renew";
    public static final String RK_ACME_RENEW                    = "acme.renew";

    // ─── Message header keys ──────────────────────────────────────────────────

    /** Header carrying the requesting service name (for observability) */
    public static final String HEADER_FROM_SERVICE = "X-From-Service";

    /** Header carrying the section name for config-save requests */
    public static final String HEADER_CONFIG_SECTION = "X-Config-Section";

    /** Header carrying the actor who made the change */
    public static final String HEADER_CHANGED_BY = "X-Changed-By";

    /** Header for tenant-scoped queries */
    public static final String HEADER_TENANT_ID = "X-Tenant-Id";

    /** Header for user-id in commands */
    public static final String HEADER_USER_ID = "X-User-Id";

    /** Header for RPC command type */
    public static final String HEADER_COMMAND = "X-Command";

    /** Timeout (ms) for synchronous RabbitMQ request-reply calls */
    public static final long REPLY_TIMEOUT_MS = 10_000L;

    // ─── routify-ai-service queues & routing keys ─────────────────────────────

    /**
     * Direct exchange owned by routify-ai-service.
     * The gateway sends AI filter evaluation requests to this exchange.
     */
    public static final String EXCHANGE_AI_SERVICE          = "routify.ai-service";

    /**
     * Queue: ai-service serves real-time route request evaluation requests from the gateway.
     * Routing key: {@value #RK_AI_FILTER_EVALUATE}
     */
    public static final String QUEUE_AI_FILTER_EVALUATE     = "routify.ai-service.filter.evaluate";
    public static final String RK_AI_FILTER_EVALUATE        = "ai.filter.evaluate";

    /**
     * Queue: ai-service serves real-time request mutation requests from the gateway.
     * Routing key: {@value #RK_AI_MODIFIER_EVALUATE}
     */
    public static final String QUEUE_AI_MODIFIER_EVALUATE   = "routify.ai-service.modifier.evaluate";
    public static final String RK_AI_MODIFIER_EVALUATE      = "ai.modifier.evaluate";

    /**
     * Timeout (ms) specific to AI filter RPC calls.
     * Tighter than the default 10s — a 3s LLM timeout + 500ms network budget.
     * The gateway falls back to the configured {@code fallbackAction} on exceed.
     */
    public static final long AI_FILTER_REPLY_TIMEOUT_MS = 3_500L;

    /**
     * Timeout (ms) for AI modifier RPC calls.
     * Slightly larger than the filter timeout because mutation responses are larger
     * (up to 1024 tokens vs 256 for the filter verdict).
     */
    public static final long AI_MODIFIER_REPLY_TIMEOUT_MS = 5_000L;

    // ─── routify-audit-service AI filter stats ────────────────────────────────

    /**
     * Queue: audit-service serves AI filter decision stats queries from admin-api.
     * Provides per-route ALLOW/BLOCK/FLAG breakdown, latency percentiles, and cache-hit rates.
     */
    public static final String QUEUE_AUDIT_AI_FILTER_STATS  = "routify.audit-service.ai-filter.stats";
    public static final String RK_AUDIT_AI_FILTER_STATS     = "audit.ai-filter.stats";

    /**
     * Queue: audit-service serves paginated AI filter decision log queries from admin-api.
     */
    public static final String QUEUE_AUDIT_AI_FILTER_QUERY  = "routify.audit-service.ai-filter.query";
    public static final String RK_AUDIT_AI_FILTER_QUERY     = "audit.ai-filter.query";

    // ─── routify-audit-service route health stats ──────────────────────────────

    /**
     * Queue: audit-service serves per-route health stats (latency percentiles, error rates,
     * status code distribution) for the Gateway Health Dashboard v2.
     */
    public static final String QUEUE_AUDIT_ROUTE_HEALTH     = "routify.audit-service.route.health";
    public static final String RK_AUDIT_ROUTE_HEALTH        = "audit.route.health";

    // ─── routify-audit-service tenant usage ──────────────────────────────────

    /** Queue: audit-service serves current-period tenant usage (route/filter/request counts vs plan limits) */
    public static final String QUEUE_AUDIT_USAGE_CURRENT     = "routify.audit-service.usage.current";
    public static final String RK_AUDIT_USAGE_CURRENT        = "audit.usage.current";

    /** Queue: audit-service serves daily usage history for a tenant (last N days) */
    public static final String QUEUE_AUDIT_USAGE_HISTORY     = "routify.audit-service.usage.history";
    public static final String RK_AUDIT_USAGE_HISTORY        = "audit.usage.history";

    // ─── routify-route-service SLO queues & routing keys ──────────────────────

    /** Queue: route-service serves route SLO config GET queries from admin-api */
    public static final String QUEUE_ROUTE_SLO_GET           = "routify.route-service.route-slo.get";
    public static final String RK_ROUTE_SLO_GET              = "route-slo.get";

    /** Queue: route-service serves route SLO config SAVE (upsert) from admin-api */
    public static final String QUEUE_ROUTE_SLO_SAVE          = "routify.route-service.route-slo.save";
    public static final String RK_ROUTE_SLO_SAVE             = "route-slo.save";
}

