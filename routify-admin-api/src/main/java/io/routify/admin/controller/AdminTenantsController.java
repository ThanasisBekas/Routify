package io.routify.admin.controller;

import io.routify.admin.client.AuditMessagingClient;
import io.routify.admin.client.IdentityMessagingClient;
import io.routify.admin.client.RouteFilterMessagingClient;
import io.routify.admin.client.RouteServiceClient;
import io.routify.admin.dto.CreateTenantRequest;
import io.routify.admin.dto.UpdateTenantRequest;
import io.routify.common.domain.TenantPlan;
import io.routify.common.event.QueryResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Admin Tenants Controller — dashboard CRUD and lifecycle management for tenants.
 *
 * <p>Read queries go via RabbitMQ to routify-identity-service.
 * Lifecycle commands (create, suspend, reactivate) use RabbitMQ sync
 * since they are rare admin actions that need immediate confirmation.
 *
 * <p>Authorization:
 * <ul>
 *   <li>List workspaces: public (no auth required — login-page dropdown)</li>
 *   <li>Read (GET list/detail): any authenticated user (VIEWER and above)</li>
 *   <li>Create/Update: SUPER_ADMIN only</li>
 *   <li>Suspend/Reactivate: SUPER_ADMIN only</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/tenants")
@RequiredArgsConstructor
public class AdminTenantsController {

    private final IdentityMessagingClient messagingClient;
    private final RouteServiceClient routeServiceClient;
    private final RouteFilterMessagingClient routeFilterClient;
    private final AuditMessagingClient auditClient;

    /**
     * Public — returns active workspace names and slugs for the login-page dropdown.
     * No authentication required.
     */
    @GetMapping("/workspaces")
    public ResponseEntity<QueryResponse.ActiveWorkspacesList> listWorkspaces() {
        return ResponseEntity.ok(messagingClient.listActiveWorkspaces());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('TENANTS_READ') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<QueryResponse.TenantsPage> listTenants(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(messagingClient.queryTenants(page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('TENANTS_READ') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<QueryResponse.TenantDetail> getTenant(@PathVariable UUID id) {
        return ResponseEntity.ok(messagingClient.getTenant(id));
    }

    /** Only SUPER_ADMIN may create new workspaces. */
    @Secured("ROLE_SUPER_ADMIN")
    @PostMapping
    public ResponseEntity<QueryResponse.TenantDetail> createTenant(
            @Valid @RequestBody CreateTenantRequest request) {
        QueryResponse.TenantDetail result = messagingClient.createTenant(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /** Suspend workspace — SUPER_ADMIN only. */
    @Secured("ROLE_SUPER_ADMIN")
    @PostMapping("/{id}/suspend")
    public ResponseEntity<QueryResponse.TenantDetail> suspendTenant(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "Administrative action") String reason) {
        return ResponseEntity.ok(messagingClient.suspendTenant(id, reason));
    }

    /** Update workspace — SUPER_ADMIN only. */
    @Secured("ROLE_SUPER_ADMIN")
    @PutMapping("/{id}")
    public ResponseEntity<QueryResponse.TenantDetail> updateTenant(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTenantRequest request) {
        return ResponseEntity.ok(messagingClient.updateTenant(id, request));
    }

    /** Reactivate workspace — SUPER_ADMIN only. */
    @Secured("ROLE_SUPER_ADMIN")
    @PostMapping("/{id}/reactivate")
    public ResponseEntity<QueryResponse.TenantDetail> reactivateTenant(@PathVariable UUID id) {
        return ResponseEntity.ok(messagingClient.reactivateTenant(id));
    }

    // ─── Tenant Usage Analytics ──────────────────────────────────────────────

    /**
     * Current usage vs plan limits for a tenant.
     * Aggregates: route count (route-service), filter count (route-service),
     * current month request count (audit-service), and plan limits (identity-service).
     */
    @GetMapping("/{id}/usage")
    @PreAuthorize("hasAuthority('TENANTS_READ') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<QueryResponse.UsageCurrentResult> getTenantUsage(@PathVariable UUID id) {
        // 1. Get tenant detail to know the plan
        QueryResponse.TenantDetail tenant = messagingClient.getTenant(id);
        if (tenant == null) {
            return ResponseEntity.notFound().build();
        }
        TenantPlan plan = tenant.plan() != null ? tenant.plan() : TenantPlan.FREE;

        // 2. Get route/filter counts from route-service
        var stats = routeServiceClient.getRouteStats(id);
        long routeCount = stats != null ? (stats.active() + stats.inactive() + stats.draft()) : 0;
        // For filter count, use the filter query (page 0, size 1 to get totalElements)
        var filtersPage = routeFilterClient.queryFilters(id, 0, 1, null, null);
        long filterCount = filtersPage != null ? filtersPage.totalElements() : 0;

        // 3. Get current month request count from audit-service
        YearMonth currentMonth = YearMonth.now(ZoneOffset.UTC);
        String monthStart = currentMonth.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC).toString();
        String monthEnd = currentMonth.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC).toString();
        var requestLogs = auditClient.queryRequestLogs(id, null, monthStart, monthEnd, 0, 1);
        long requestCount = requestLogs != null ? requestLogs.totalElements() : 0;

        // 4. Build response
        var routes = new QueryResponse.UsageCurrentResult.QuotaDimension(
                routeCount, plan.maxRoutes(), percentage(routeCount, plan.maxRoutes()));
        var filters = new QueryResponse.UsageCurrentResult.QuotaDimension(
                filterCount, plan.maxFilters(), percentage(filterCount, plan.maxFilters()));
        var requests = new QueryResponse.UsageCurrentResult.QuotaDimension(
                requestCount, plan.monthlyRequestQuota(), percentage(requestCount, plan.monthlyRequestQuota()));

        return ResponseEntity.ok(new QueryResponse.UsageCurrentResult(
                id, plan.name(), routes, filters, requests,
                currentMonth.atDay(1).toString(),
                currentMonth.atEndOfMonth().toString()));
    }

    /**
     * Daily usage history for a tenant (default 30 days).
     */
    @GetMapping("/{id}/usage/history")
    @PreAuthorize("hasAuthority('TENANTS_READ') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<QueryResponse.UsageHistoryResult> getTenantUsageHistory(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(auditClient.queryUsageHistory(id, days));
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private static int percentage(long used, int limit) {
        if (limit == Integer.MAX_VALUE || limit <= 0) return 0;
        return (int) Math.min(100, (used * 100) / limit);
    }
}
