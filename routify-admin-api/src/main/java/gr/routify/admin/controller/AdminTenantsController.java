package gr.routify.admin.controller;

import gr.routify.admin.client.IdentityMessagingClient;
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
    public Map<String, Object> listWorkspaces() {
        return messagingClient.listActiveWorkspaces();
    }

    @GetMapping
    public Map<String, Object> listTenants(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return messagingClient.queryTenants(page, size);
    }

    @GetMapping("/{id}")
    public Map<String, Object> getTenant(@PathVariable UUID id) {
        return messagingClient.getTenant(id);
    }

    /** Only SUPER_ADMIN may create new workspaces. */
    @Secured("ROLE_SUPER_ADMIN")
    @PostMapping
    public ResponseEntity<Map<String, Object>> createTenant(
            @Valid @RequestBody Map<String, Object> request) {
        Map<String, Object> result = messagingClient.tenantCommand("CREATE_TENANT", null, request);
        if (result.containsKey("error")) {
            // errorJson() from IdentityRabbitHandler uses e.getClass().getSimpleName() as "code".
            // RoutifyException inner classes are named "Conflict", "NotFound", "Forbidden", etc.
            String message = String.valueOf(result.get("error"));
            String code    = String.valueOf(result.getOrDefault("code", ""));
            HttpStatus status = switch (code) {
                case "Conflict"          -> HttpStatus.CONFLICT;
                case "NotFound"          -> HttpStatus.NOT_FOUND;
                case "Forbidden"         -> HttpStatus.FORBIDDEN;
                case "Unauthorized"      -> HttpStatus.UNAUTHORIZED;
                case "Validation",
                     "BadRequest"        -> HttpStatus.UNPROCESSABLE_ENTITY;
                case "QuotaExceeded"     -> HttpStatus.PAYMENT_REQUIRED;
                default -> Boolean.TRUE.equals(result.get("circuitOpen"))
                        ? HttpStatus.SERVICE_UNAVAILABLE
                        : HttpStatus.UNPROCESSABLE_ENTITY;
            };
            return ResponseEntity.status(status)
                    .body(Map.of("detail", message, "status", status.value(), "code", code));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PostMapping("/{id}/suspend")
    public Map<String, Object> suspendTenant(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "Administrative action") String reason) {
        return messagingClient.tenantCommand("SUSPEND_TENANT", id, Map.of("reason", reason));
    }

    @PostMapping("/{id}/reactivate")
    public Map<String, Object> reactivateTenant(@PathVariable UUID id) {
        return messagingClient.tenantCommand("REACTIVATE_TENANT", id, Map.of());
    }
}
