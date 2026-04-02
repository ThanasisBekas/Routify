package gr.routify.common.event;

/**
 * Kafka topic constants used across all Routify services.
 * Centralised here to prevent string drift between producers and consumers.
 */
public final class KafkaTopics {

    private KafkaTopics() {}

    /** Route lifecycle events → consumed by routify-api-gateway for hot-reload */
    public static final String ROUTE_EVENTS = "routify.route.events";

    /** Filter lifecycle events → consumed by routify-api-gateway for filter chain rebuild */
    public static final String FILTER_EVENTS = "routify.filter.events";

    /** Tenant lifecycle events → consumed by all services */
    public static final String TENANT_EVENTS = "routify.tenant.events";

    /** User lifecycle events */
    public static final String USER_EVENTS = "routify.user.events";

    /**
     * Gateway reload requests — published when route/filter changes require
     * a forced gateway reload, e.g. certificate rotation.
     */
    public static final String GATEWAY_RELOAD = "routify.gateway.reload";

    /**
     * Gateway configuration changes — published by routify-admin-api when
     * any gateway config section (CORS, security headers, rate-limit, etc.) is saved.
     * All running gateway instances consume this to reload config from the database,
     * ensuring configuration survives restarts and rolling deployments.
     */
    public static final String GATEWAY_CONFIG_EVENTS = "routify.gateway.config";

    /** Audit trail events → consumed by routify-audit-service */
    public static final String AUDIT_EVENTS = "routify.audit.events";

    /** Request/response telemetry → consumed by routify-audit-service */
    public static final String REQUEST_TELEMETRY = "routify.request.telemetry";

    /** Dead letter queue for failed event processing */
    public static final String DLQ = "routify.dlq";

    // ─── Admin-API Command Topics ─────────────────────────────────────────────
    // Commands are published by routify-admin-api and consumed by the owning service.
    // They represent write operations from the dashboard.

    /**
     * Route command events — published by routify-admin-api when the dashboard
     * creates, updates, activates, deactivates, or deletes a route.
     * Consumed by routify-route-service which executes the mutation.
     */
    public static final String ROUTE_COMMANDS = "routify.route.commands";

    /**
     * Filter command events — published by routify-admin-api when the dashboard
     * creates, updates, deletes, attaches or detaches a filter.
     * Consumed by routify-route-service.
     */
    public static final String FILTER_COMMANDS = "routify.filter.commands";

    /**
     * User command events — published by routify-admin-api when the dashboard
     * creates, updates, or deletes a user.
     * Consumed by routify-identity-service.
     */
    public static final String USER_COMMANDS = "routify.user.commands";

    /**
     * Tenant command events — published by routify-admin-api when the dashboard
     * creates, suspends, or reactivates a tenant.
     * Consumed by routify-identity-service.
     */
    public static final String TENANT_COMMANDS = "routify.tenant.commands";

    /**
     * Auth command events — published by routify-admin-api on logout.
     * Consumed by routify-identity-service to blacklist the refresh token JTI in Redis.
     */
    public static final String AUTH_COMMANDS = "routify.auth.commands";

    // ─── Certificate Vault Topics ─────────────────────────────────────────────

    /**
     * Certificate domain events — published by routify-cert-vault when a certificate
     * is uploaded, revoked, deleted, or mapped to a gateway TLS entry.
     * Consumed by routify-api-gateway to trigger TLS config hot-reload.
     */
    public static final String CERT_EVENTS = "routify.cert.events";

    /**
     * Certificate command events — published by routify-admin-api when the dashboard
     * uploads, revokes, deletes, or maps a certificate.
     * Consumed by routify-cert-vault which executes the mutation.
     */
    public static final String CERT_COMMANDS = "routify.cert.commands";

    /**
     * Certificate group domain events — published by routify-cert-vault when a group
     * is created, updated, archived, deleted, or its members change.
     * Consumed by routify-api-gateway to keep the TLS registry in sync with group membership.
     */
    public static final String CERT_GROUP_EVENTS = "routify.cert.group.events";
}

