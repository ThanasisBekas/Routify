package gr.routify.common.security;

import java.util.UUID;

/**
 * Immutable security context propagated via request-scoped bean or thread-local.
 * Populated by the JWT filter on every authenticated request.
 *
 * <p>Usage:
 * <pre>{@code
 * var ctx = SecurityContext.current();
 * if (ctx.hasRole(UserRole.TENANT_ADMIN)) { ... }
 * }</pre>
 */
public record SecurityContext(
        UUID userId,
        UUID tenantId,
        String username,
        String role,
        String correlationId
) {
    private static final ThreadLocal<SecurityContext> HOLDER = new ThreadLocal<>();

    public static void set(SecurityContext ctx) {
        HOLDER.set(ctx);
    }

    public static SecurityContext current() {
        SecurityContext ctx = HOLDER.get();
        if (ctx == null) {
            throw new IllegalStateException("No SecurityContext set on current thread");
        }
        return ctx;
    }

    public static void clear() {
        HOLDER.remove();
    }

    public boolean hasRole(String requiredRole) {
        return requiredRole.equalsIgnoreCase(this.role);
    }

    public boolean isSuperAdmin() {
        return "SUPER_ADMIN".equalsIgnoreCase(this.role);
    }

    public boolean isTenantAdmin() {
        return "TENANT_ADMIN".equalsIgnoreCase(this.role) || isSuperAdmin();
    }
}

