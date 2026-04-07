package io.routify.common.domain;

/**
 * Catalog of platform event types that can trigger webhook notifications.
 *
 * <p>Tenants subscribe to one or more of these event types when configuring
 * a {@code WebhookSubscription}. The identity-service's {@code WebhookEventConsumer}
 * maps incoming Kafka domain events to these types and dispatches matching webhooks.
 */
public enum WebhookEventType {
    // ─── Route events ─────────────────────────────────────────────────────────
    ROUTE_CREATED,
    ROUTE_ACTIVATED,
    ROUTE_DEACTIVATED,
    ROUTE_DELETED,
    ROUTE_PROMOTED,

    // ─── Filter events ────────────────────────────────────────────────────────
    FILTER_CREATED,
    FILTER_UPDATED,
    FILTER_DELETED,

    // ─── Certificate events ───────────────────────────────────────────────────
    CERT_UPLOADED,
    CERT_REVOKED,
    CERT_EXPIRING,
    CERT_EXPIRED,

    // ─── User / Tenant events ─────────────────────────────────────────────────
    USER_CREATED,
    USER_DELETED,
    TENANT_SUSPENDED,
    TENANT_REACTIVATED,

    // ─── AI events ────────────────────────────────────────────────────────────
    AI_FILTER_BLOCKED,
    AI_FILTER_FLAGGED,

    // ─── Infrastructure events ────────────────────────────────────────────────
    /** Triggered when more than N DLQ events are observed in a time window. */
    DLQ_OVERFLOW,
    GATEWAY_RELOAD_FAILED,
    /** Triggered when a gateway instance's config version is behind the global version for &gt;60s. */
    GATEWAY_CONFIG_DRIFT,

    // ─── Quota events ──────────────────────────────────────────────────────────
    /** Triggered when a tenant reaches 80% of any quota (routes, filters, or requests). */
    QUOTA_WARNING,
    /** Triggered when a tenant reaches 100% of any quota (routes, filters, or requests). */
    QUOTA_EXCEEDED,

    // ─── Canary routing events (Initiative 14) ──────────────────────────────────
    CANARY_DEPLOYED,
    CANARY_PROMOTED,
    CANARY_ROLLBACK
}

