package io.routify.common.security;

import org.slf4j.MDC;

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
 *
 * <h2>MDC enrichment</h2>
 * Call {@link #setMdc()} after {@link #set(SecurityContext)} to populate the SLF4J MDC
 * with {@code userId}, {@code tenantId}, and {@code correlationId}. Call {@link #clearMdc()}
 * (or {@link #clear()}, which does both) when the scope ends. The log pattern
 * {@code %X{userId} %X{tenantId} %X{correlationId}} renders these automatically.
 */
public record SecurityContext(
        UUID userId,
        UUID tenantId,
        String username,
        String role,
        String correlationId
) {
    /** MDC key for the authenticated user ID. */
    public static final String MDC_USER_ID = "userId";
    /** MDC key for the tenant ID. */
    public static final String MDC_TENANT_ID = "tenantId";
    /** MDC key for the correlation / trace ID. */
    public static final String MDC_CORRELATION_ID = "correlationId";

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
        clearMdc();
    }

    /**
     * Populates the SLF4J MDC with {@code userId}, {@code tenantId}, and
     * {@code correlationId} from this context instance. Safe to call with
     * {@code null} field values — they are silently skipped.
     */
    public void setMdc() {
        if (userId != null) MDC.put(MDC_USER_ID, userId.toString());
        if (tenantId != null) MDC.put(MDC_TENANT_ID, tenantId.toString());
        if (correlationId != null && !correlationId.isBlank()) MDC.put(MDC_CORRELATION_ID, correlationId);
    }

    /**
     * Removes all Routify-managed MDC keys ({@code userId}, {@code tenantId},
     * {@code correlationId}) from the current thread.
     */
    public static void clearMdc() {
        MDC.remove(MDC_USER_ID);
        MDC.remove(MDC_TENANT_ID);
        MDC.remove(MDC_CORRELATION_ID);
    }

    /**
     * Convenience: puts only the given values into MDC without requiring a full
     * {@link SecurityContext}. Useful in Kafka consumers that extract identifiers
     * from record headers or command payloads.
     */
    public static void putMdc(String userId, String tenantId, String correlationId) {
        if (userId != null && !userId.isBlank()) MDC.put(MDC_USER_ID, userId);
        if (tenantId != null && !tenantId.isBlank()) MDC.put(MDC_TENANT_ID, tenantId);
        if (correlationId != null && !correlationId.isBlank()) MDC.put(MDC_CORRELATION_ID, correlationId);
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

