package io.routify.common.domain;

/**
 * Lifecycle status of a gateway Route.
 * DRAFT → ACTIVE (zero-downtime hot-reload) → DISABLED
 */
public enum RouteStatus {
    DRAFT,
    ACTIVE,
    DISABLED,
    ARCHIVED
}

