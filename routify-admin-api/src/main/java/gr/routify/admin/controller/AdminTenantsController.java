package gr.routify.admin.controller;

import gr.routify.admin.client.IdentityMessagingClient;
import gr.routify.common.event.QueryResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Admin Tenants Controller — dashboard CRUD and lifecycle management for tenants.
 *
 * <p>Read queries go via RabbitMQ to routify-identity-service.
 * Lifecycle commands (create, suspend, reactivate) use RabbitMQ sync
 * since they are rare admin actions that need immediate confirmation.
 *
 * <p>Creating a workspace is restricted to {@code SUPER_ADMIN} only.
 */
@RestController
@RequestMapping("/api/v1/admin/tenants")
@RequiredArgsConstructor
public class AdminTenantsController {

    private final IdentityMessagingClient messagingClient;

    /**
     * Public — returns active workspace names and slugs for the login-page dropdown.
     * No authentication required.
     */
    @GetMapping("/workspaces")
    public QueryResponse.ActiveWorkspacesList listWorkspaces() {
        return messagingClient.listActiveWorkspaces();
    }

    @GetMapping
    public QueryResponse.TenantsPage listTenants(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return messagingClient.queryTenants(page, size);
    }

    @GetMapping("/{id}")
    public QueryResponse.TenantDetail getTenant(@PathVariable UUID id) {
        return messagingClient.getTenant(id);
    }

    /** Only SUPER_ADMIN may create new workspaces. */
    @Secured("ROLE_SUPER_ADMIN")
    @PostMapping
    public ResponseEntity<QueryResponse.TenantDetail> createTenant(
            @Valid @RequestBody Map<String, Object> request) {
        QueryResponse.TenantDetail result = messagingClient.tenantCommand("CREATE_TENANT", null, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PostMapping("/{id}/suspend")
    public QueryResponse.TenantDetail suspendTenant(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "Administrative action") String reason) {
        return messagingClient.tenantCommand("SUSPEND_TENANT", id, Map.of("reason", reason));
    }

    /** Update workspace — SUPER_ADMIN only. */
    @Secured("ROLE_SUPER_ADMIN")
    @PutMapping("/{id}")
    public QueryResponse.TenantDetail updateTenant(
            @PathVariable UUID id,
            @Valid @RequestBody Map<String, Object> request) {
        return messagingClient.tenantCommand("UPDATE_TENANT", id, request);
    }

    @PostMapping("/{id}/reactivate")
    public QueryResponse.TenantDetail reactivateTenant(@PathVariable UUID id) {
        return messagingClient.tenantCommand("REACTIVATE_TENANT", id, Map.of());
    }
}
