package io.routify.admin.controller;

import io.routify.admin.client.IdentityMessagingClient;
import io.routify.admin.dto.CreateUserRequest;
import io.routify.admin.dto.ResetPasswordRequest;
import io.routify.admin.dto.UpdateUserRequest;
import io.routify.common.event.QueryResponse;
import io.routify.common.web.AsyncAcknowledgement;
import io.routify.common.web.RoutifyHeaders;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
 *
 * <p>Authorization:
 * <ul>
 *   <li>Read (GET): any authenticated user (VIEWER and above)</li>
 *   <li>Write (POST/PUT/DELETE): TENANT_ADMIN or SUPER_ADMIN only</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUsersController {

    private final IdentityMessagingClient messagingClient;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<QueryResponse.UsersPage> listUsers(
            @RequestHeader(value = RoutifyHeaders.TENANT_ID, required = false) UUID tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(messagingClient.queryUsers(tenantId, page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<QueryResponse.UserDetail> getUser(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId) {
        return ResponseEntity.ok(messagingClient.getUser(id, tenantId));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<AsyncAcknowledgement> createUser(
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @Valid @RequestBody CreateUserRequest request,
            Authentication auth) {
        String actor = RoutifyHeaders.resolveActor(null, auth != null ? auth.getName() : null);
        messagingClient.sendCreateUser(tenantId, actor, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("User creation in progress"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<AsyncAcknowledgement> updateUser(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @Valid @RequestBody UpdateUserRequest request,
            Authentication auth) {
        String actor = RoutifyHeaders.resolveActor(null, auth != null ? auth.getName() : null);
        messagingClient.sendUpdateUser(id, tenantId, actor, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("User update in progress"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<AsyncAcknowledgement> deleteUser(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            Authentication auth) {
        String actor = RoutifyHeaders.resolveActor(null, auth != null ? auth.getName() : null);
        messagingClient.sendDeleteUser(id, tenantId, actor);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("User deletion in progress"));
    }

    /**
     * Admin-initiated password reset — sets a temporary password and forces
     * the target user to change it on next login ({@code mustChangePassword=true}).
     */
    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<?> resetPassword(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @Valid @RequestBody ResetPasswordRequest body,
            Authentication auth) {

        QueryResponse.PasswordChangeResult result =
                messagingClient.adminResetPassword(id, tenantId, body.newPassword());
        if (result == null || !result.success()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Password reset failed — identity-service unavailable"));
        }
        return ResponseEntity.ok(Map.of("success", true,
                "message", "Password reset. User must change password on next login."));
    }
}
