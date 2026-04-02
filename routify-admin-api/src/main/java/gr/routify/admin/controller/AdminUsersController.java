package gr.routify.admin.controller;

import gr.routify.admin.client.IdentityMessagingClient;
import gr.routify.common.event.QueryResponse;
import gr.routify.common.web.AsyncAcknowledgement;
import gr.routify.common.web.RoutifyHeaders;
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
    public ResponseEntity<QueryResponse.UsersPage> listUsers(
            @RequestHeader(value = RoutifyHeaders.TENANT_ID, required = false) UUID tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(messagingClient.queryUsers(tenantId, page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<QueryResponse.UserDetail> getUser(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId) {
        return ResponseEntity.ok(messagingClient.getUser(id, tenantId));
    }

    @PostMapping
    public ResponseEntity<AsyncAcknowledgement> createUser(
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @Valid @RequestBody Map<String, Object> request,
            Authentication auth) {
        String actor = auth != null ? auth.getName() : "system";
        messagingClient.sendCreateUser(tenantId, actor, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("User creation in progress"));
    }

    @PutMapping("/{id}")
    public ResponseEntity<AsyncAcknowledgement> updateUser(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @Valid @RequestBody Map<String, Object> request,
            Authentication auth) {
        String actor = auth != null ? auth.getName() : "system";
        messagingClient.sendUpdateUser(id, tenantId, actor, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("User update in progress"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<AsyncAcknowledgement> deleteUser(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            Authentication auth) {
        String actor = auth != null ? auth.getName() : "system";
        messagingClient.sendDeleteUser(id, tenantId, actor);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("User deletion in progress"));
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
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
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
