package gr.routify.admin.controller;

import gr.routify.admin.client.IdentityMessagingClient;
import gr.routify.common.event.QueryResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Admin Users Controller — dashboard CRUD for users.
 *
 * <p>Read queries go via RabbitMQ to routify-identity-service.
 * Write commands (create, update, delete) are published as Kafka events
 * to routify-identity-service.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUsersController {

    private final IdentityMessagingClient messagingClient;

    @GetMapping
    public QueryResponse.UsersPage listUsers(
            @RequestHeader(value = "X-Tenant-Id", required = false) UUID tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return messagingClient.queryUsers(tenantId, page, size);
    }

    @GetMapping("/{id}")
    public QueryResponse.UserDetail getUser(
            @PathVariable UUID id,
            @RequestHeader("X-Tenant-Id") UUID tenantId) {
        return messagingClient.getUser(id, tenantId);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createUser(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @Valid @RequestBody Map<String, Object> request,
            Authentication auth) {
        String actor = auth != null ? auth.getName() : "system";
        messagingClient.sendCreateUser(tenantId, actor, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("status", "accepted", "message", "User creation in progress"));
    }

    @PutMapping("/{id}")
    public Map<String, Object> updateUser(
            @PathVariable UUID id,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @Valid @RequestBody Map<String, Object> request,
            Authentication auth) {
        String actor = auth != null ? auth.getName() : "system";
        messagingClient.sendUpdateUser(id, tenantId, actor, request);
        return Map.of("status", "accepted", "message", "User update in progress");
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> deleteUser(
            @PathVariable UUID id,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            Authentication auth) {
        String actor = auth != null ? auth.getName() : "system";
        messagingClient.sendDeleteUser(id, tenantId, actor);
        return Map.of("status", "accepted", "message", "User deletion in progress");
    }

    /**
     * Admin-initiated password reset — sets a temporary password and forces
     * the target user to change it on next login ({@code mustChangePassword=true}).
     *
     * <p>Only users with {@code TENANT_ADMIN} or {@code SUPER_ADMIN} role should be
     * able to call this endpoint; role enforcement is handled in the security layer.
     */
    @PostMapping("/{id}/reset-password")
    public ResponseEntity<?> resetPassword(
            @PathVariable UUID id,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestBody Map<String, Object> body,
            Authentication auth) {

        String newPassword = body.get("newPassword") != null ? body.get("newPassword").toString() : "";
        if (newPassword.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "newPassword is required"));
        }

        QueryResponse.PasswordChangeResult result =
                messagingClient.adminResetPassword(id, tenantId, newPassword);
        if (result == null || !result.success()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Password reset failed — identity-service unavailable"));
        }
        return ResponseEntity.ok(Map.of("success", true,
                "message", "Password reset. User must change password on next login."));
    }
}
