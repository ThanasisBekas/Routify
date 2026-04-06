package io.routify.admin.controller;

import io.routify.admin.client.IdentityMessagingClient;
import io.routify.admin.dto.CreateTenantRequest;
import io.routify.admin.dto.UpdateTenantRequest;
import io.routify.common.event.QueryResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

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
}
