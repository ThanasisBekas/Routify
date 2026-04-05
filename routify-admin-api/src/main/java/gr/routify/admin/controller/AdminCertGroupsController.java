package gr.routify.admin.controller;

import gr.routify.admin.client.CertVaultMessagingClient;
import gr.routify.admin.dto.AddCertToGroupRequest;
import gr.routify.admin.dto.CreateCertGroupRequest;
import gr.routify.admin.dto.UpdateCertGroupRequest;
import gr.routify.common.event.QueryResponse;
import gr.routify.common.web.AsyncAcknowledgement;
import gr.routify.common.web.RoutifyHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Admin Certificate Groups Controller — dashboard management of certificate groups.
 *
 * <p>Read operations are served via RabbitMQ request/reply to routify-cert-vault.
 * Write operations are published as Kafka command events to routify-cert-vault.
 * There are NO direct HTTP calls to cert-vault.
 *
 * <p>Authorization:
 * <ul>
 *   <li>Read (GET): any authenticated user (VIEWER and above)</li>
 *   <li>Write (POST/PUT/DELETE): OPERATOR, TENANT_ADMIN, or SUPER_ADMIN</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/cert-groups")
@RequiredArgsConstructor
public class AdminCertGroupsController {

    private final CertVaultMessagingClient messagingClient;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<QueryResponse.CertGroupsPage> listGroups(
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDir) {
        return ResponseEntity.ok(
                messagingClient.queryCertGroups(tenantId, status, page, size, sortBy, sortDir));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<QueryResponse.CertGroupDetail> getGroup(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId) {
        return ResponseEntity.ok(messagingClient.getCertGroup(id, tenantId));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
    public ResponseEntity<AsyncAcknowledgement> createGroup(
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestHeader(value = RoutifyHeaders.USER_ID, required = false) String userId,
            @RequestBody CreateCertGroupRequest request,
            Authentication auth) {
        String actor = RoutifyHeaders.resolveActor(userId, auth != null ? auth.getName() : null);
        messagingClient.sendCreateCertGroup(tenantId, actor, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("Certificate group creation in progress"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
    public ResponseEntity<AsyncAcknowledgement> updateGroup(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestHeader(value = RoutifyHeaders.USER_ID, required = false) String userId,
            @RequestBody UpdateCertGroupRequest request,
            Authentication auth) {
        String actor = RoutifyHeaders.resolveActor(userId, auth != null ? auth.getName() : null);
        messagingClient.sendUpdateCertGroup(id, tenantId, actor, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("Certificate group update in progress"));
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
    public ResponseEntity<AsyncAcknowledgement> archiveGroup(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestHeader(value = RoutifyHeaders.USER_ID, required = false) String userId,
            Authentication auth) {
        String actor = RoutifyHeaders.resolveActor(userId, auth != null ? auth.getName() : null);
        messagingClient.sendArchiveCertGroup(id, tenantId, actor);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("Certificate group archival in progress"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
    public ResponseEntity<AsyncAcknowledgement> deleteGroup(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestHeader(value = RoutifyHeaders.USER_ID, required = false) String userId,
            Authentication auth) {
        String actor = RoutifyHeaders.resolveActor(userId, auth != null ? auth.getName() : null);
        messagingClient.sendDeleteCertGroup(id, tenantId, actor);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("Certificate group deletion in progress"));
    }

    @GetMapping("/{id}/members")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<QueryResponse.CertGroupMembersList> listGroupMembers(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId) {
        return ResponseEntity.ok(messagingClient.listCertGroupMembers(id, tenantId));
    }

    @PostMapping("/{id}/members")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
    public ResponseEntity<AsyncAcknowledgement> addMemberToGroup(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestHeader(value = RoutifyHeaders.USER_ID, required = false) String userId,
            @RequestBody AddCertToGroupRequest request,
            Authentication auth) {
        String actor = RoutifyHeaders.resolveActor(userId, auth != null ? auth.getName() : null);
        messagingClient.sendAddCertToGroup(id, tenantId, actor, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("Certificate group member addition in progress"));
    }

    @DeleteMapping("/{id}/members/{certId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
    public ResponseEntity<AsyncAcknowledgement> removeMemberFromGroup(
            @PathVariable UUID id,
            @PathVariable UUID certId,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestHeader(value = RoutifyHeaders.USER_ID, required = false) String userId,
            Authentication auth) {
        String actor = RoutifyHeaders.resolveActor(userId, auth != null ? auth.getName() : null);
        messagingClient.sendRemoveCertFromGroup(id, certId, tenantId, actor);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("Certificate group member removal in progress"));
    }
}
