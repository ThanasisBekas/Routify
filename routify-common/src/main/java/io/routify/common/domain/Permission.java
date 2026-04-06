package io.routify.common.domain;

/**
 * Fine-grained permission constants for the Routify RBAC system.
 *
 * <p>Each permission represents a specific action on a resource type.
 * Built-in roles map to default permission sets; custom roles can combine
 * any subset of these permissions.
 *
 * <p>Permissions are embedded as a string array in the JWT {@code "permissions"}
 * claim when the {@code routify.rbac.granular-enabled} feature flag is active.
 *
 * @see io.routify.common.security.SecurityContext#hasPermission(Permission)
 */
public enum Permission {

    // ─── Routes ────────────────────────────────────────────────────────────────
    ROUTES_READ,
    ROUTES_WRITE,
    ROUTES_ACTIVATE,
    ROUTES_DELETE,
    ROUTES_PROMOTE,

    // ─── Filters ───────────────────────────────────────────────────────────────
    FILTERS_READ,
    FILTERS_WRITE,
    FILTERS_DELETE,

    // ─── Users ─────────────────────────────────────────────────────────────────
    USERS_READ,
    USERS_WRITE,
    USERS_DELETE,

    // ─── Certificates ──────────────────────────────────────────────────────────
    CERTS_READ,
    CERTS_WRITE,
    CERTS_ADMIN,

    // ─── Audit ─────────────────────────────────────────────────────────────────
    AUDIT_READ,
    AUDIT_REPLAY,

    // ─── Gateway config ────────────────────────────────────────────────────────
    GATEWAY_CONFIG_READ,
    GATEWAY_CONFIG_WRITE,

    // ─── API keys ──────────────────────────────────────────────────────────────
    API_KEYS_READ,
    API_KEYS_ADMIN,

    // ─── Webhooks ──────────────────────────────────────────────────────────────
    WEBHOOKS_READ,
    WEBHOOKS_ADMIN,

    // ─── AI ────────────────────────────────────────────────────────────────────
    AI_POLICY_READ,
    AI_POLICY_WRITE,

    // ─── Tenants (SUPER_ADMIN only) ────────────────────────────────────────────
    TENANTS_READ,
    TENANTS_WRITE,
    TENANTS_SUSPEND;

    /**
     * Returns the permission code as used in JWT claims and authority strings.
     * Identical to {@link #name()} — kept explicit for clarity at call sites.
     */
    public String code() {
        return name();
    }
}

