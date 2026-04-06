package io.routify.common.event;

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

    /**
     * AI filter verdict events — published by routify-ai-service after every evaluation.
     * Consumed by routify-audit-service for compliance persistence and analytics.
     */
    public static final String AI_FILTER_DECISIONS = "routify.ai.filter.decisions";

    /**
     * AI modification decision events — published by routify-ai-service after every
     * mutation evaluation.  Consumed by routify-audit-service for compliance persistence
     * and analytics.  The original request body is never included — only its SHA-256 hash.
     */
    public static final String AI_MODIFICATION_EVENTS = "routify.ai.modification.events";


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

    // ─── Dead-Letter Queue Topics ─────────────────────────────────────────────
    // Naming convention: <original-topic>.DLQ
    // All services forward unprocessable records here after exhausting retries.
    // Consumed exclusively by routify-audit-service for persistence and alerting.

    public static final String DLQ_ROUTE_EVENTS       = ROUTE_EVENTS       + ".DLQ";
    public static final String DLQ_FILTER_EVENTS      = FILTER_EVENTS      + ".DLQ";
    public static final String DLQ_TENANT_EVENTS      = TENANT_EVENTS      + ".DLQ";
    public static final String DLQ_USER_EVENTS        = USER_EVENTS        + ".DLQ";
    public static final String DLQ_GATEWAY_RELOAD     = GATEWAY_RELOAD     + ".DLQ";
    public static final String DLQ_GATEWAY_CONFIG     = GATEWAY_CONFIG_EVENTS + ".DLQ";
    public static final String DLQ_CERT_EVENTS        = CERT_EVENTS        + ".DLQ";
    public static final String DLQ_CERT_GROUP_EVENTS  = CERT_GROUP_EVENTS  + ".DLQ";
    public static final String DLQ_REQUEST_TELEMETRY  = REQUEST_TELEMETRY  + ".DLQ";
    public static final String DLQ_ROUTE_COMMANDS     = ROUTE_COMMANDS     + ".DLQ";
    public static final String DLQ_FILTER_COMMANDS    = FILTER_COMMANDS    + ".DLQ";
    public static final String DLQ_USER_COMMANDS      = USER_COMMANDS      + ".DLQ";
    public static final String DLQ_TENANT_COMMANDS    = TENANT_COMMANDS    + ".DLQ";
    public static final String DLQ_AUTH_COMMANDS      = AUTH_COMMANDS      + ".DLQ";
    public static final String DLQ_CERT_COMMANDS      = CERT_COMMANDS      + ".DLQ";

    /** DLQ for AI filter decision events */
    public static final String DLQ_AI_FILTER_DECISIONS     = AI_FILTER_DECISIONS     + ".DLQ";

    /** DLQ for AI modification events */
    public static final String DLQ_AI_MODIFICATION_EVENTS  = AI_MODIFICATION_EVENTS  + ".DLQ";
}

