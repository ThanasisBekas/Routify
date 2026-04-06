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
    GATEWAY_RELOAD_FAILED
}

