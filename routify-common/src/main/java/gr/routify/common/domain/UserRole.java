package gr.routify.common.domain;

/**
 * User roles within the Routify platform.
 * Uses Java 21 pattern matching and sealed classes in switch expressions.
 */
public enum UserRole {
    /** Super admin — full platform access, can manage tenants */
    SUPER_ADMIN,
    /** Tenant admin — full access within their tenant */
    TENANT_ADMIN,
    /** Read-only viewer — can view routes/filters but not modify */
    VIEWER,
    /** Operator — can enable/disable routes but not create */
    OPERATOR
}

