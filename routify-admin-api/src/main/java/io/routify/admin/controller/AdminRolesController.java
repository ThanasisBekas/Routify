package io.routify.admin.controller;

import io.routify.admin.client.IdentityMessagingClient;
import io.routify.common.domain.Permission;
import io.routify.common.event.QueryResponse;
import io.routify.common.web.RoutifyHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin Roles Controller — dashboard CRUD for role definitions.
 *
 * <p>Role queries go via RabbitMQ to routify-identity-service.
 * Role mutations (create/update/delete) go via RabbitMQ sync RPC.
 *
 * <p>Authorization:
 * <ul>
 *   <li>Read (GET): any authenticated user</li>
 *   <li>Write (POST/PUT/DELETE): TENANT_ADMIN or SUPER_ADMIN</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/roles")
@RequiredArgsConstructor
public class AdminRolesController {

    private final IdentityMessagingClient messagingClient;

    @GetMapping
    @PreAuthorize("hasAuthority('USERS_READ') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<QueryResponse.RolesPage> listRoles(
            @RequestHeader(value = RoutifyHeaders.TENANT_ID, required = false) UUID tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(messagingClient.queryRoles(tenantId, page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('USERS_READ') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<QueryResponse.RoleDetail> getRole(@PathVariable UUID id) {
        return ResponseEntity.ok(messagingClient.getRole(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('USERS_WRITE') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<QueryResponse.RoleDetail> createRole(
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        String name = (String) body.get("name");
        String description = (String) body.get("description");
        @SuppressWarnings("unchecked")
        List<String> permissions = (List<String>) body.get("permissions");
        return ResponseEntity.ok(messagingClient.createRole(tenantId, name, description, permissions));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('USERS_WRITE') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<QueryResponse.RoleDetail> updateRole(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        String name = (String) body.get("name");
        String description = (String) body.get("description");
        @SuppressWarnings("unchecked")
        List<String> permissions = (List<String>) body.get("permissions");
        return ResponseEntity.ok(messagingClient.updateRole(id, tenantId, name, description, permissions));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('USERS_WRITE') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<Void> deleteRole(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            Authentication auth) {
        messagingClient.deleteRole(id, tenantId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Returns the full list of available permissions — useful for role editor forms.
     */
    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('USERS_READ') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<List<String>> listPermissions() {
        List<String> permissions = Arrays.stream(Permission.values())
                .map(Permission::name)
                .toList();
        return ResponseEntity.ok(permissions);
    }
}

