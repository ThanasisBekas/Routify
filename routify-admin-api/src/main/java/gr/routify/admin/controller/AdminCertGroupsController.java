package gr.routify.admin.controller;

import gr.routify.admin.client.CertVaultMessagingClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Admin Certificate Groups Controller — dashboard management of certificate groups.
 *
 * <p>A certificate group is a logical container that groups one or more vault certificates
 * under a single stable {@code logicalId}. The group's logical ID is what the gateway
 * TLS registry and filters bind to — enabling certificate rotation and multi-cert grouping.
 *
 * <p>Read operations are served via RabbitMQ request/reply to routify-cert-vault.
 * Write operations are published as Kafka command events to routify-cert-vault.
 * There are NO direct HTTP calls to cert-vault.
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>GET  /api/v1/admin/cert-groups — paginated list of groups</li>
 *   <li>GET  /api/v1/admin/cert-groups/{id} — single group with members</li>
 *   <li>POST /api/v1/admin/cert-groups — create a new group</li>
 *   <li>PUT  /api/v1/admin/cert-groups/{id} — update group alias/description</li>
 *   <li>POST /api/v1/admin/cert-groups/{id}/archive — archive a group</li>
 *   <li>DELETE /api/v1/admin/cert-groups/{id} — delete a group</li>
 *   <li>GET  /api/v1/admin/cert-groups/{id}/members — list group members</li>
 *   <li>POST /api/v1/admin/cert-groups/{id}/members — add a certificate to group</li>
 *   <li>DELETE /api/v1/admin/cert-groups/{id}/members/{certId} — remove cert from group</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/cert-groups")
@RequiredArgsConstructor
public class AdminCertGroupsController {

    private final CertVaultMessagingClient messagingClient;

    // ─── List groups ──────────────────────────────────────────────────────────

    @GetMapping
    public Map<String, Object> listGroups(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDir) {
        return messagingClient.queryCertGroups(tenantId, status, page, size, sortBy, sortDir);
    }

    // ─── Get single group (with members) ─────────────────────────────────────

    @GetMapping("/{id}")
    public Map<String, Object> getGroup(
            @PathVariable UUID id,
            @RequestHeader("X-Tenant-Id") UUID tenantId) {
        return messagingClient.getCertGroup(id, tenantId);
    }

    // ─── Create group ─────────────────────────────────────────────────────────

    /**
     * Create a new certificate group.
     *
     * <p>Request body:
     * <pre>{@code
     * {
     *   "logicalId":   "my-inbound-tls",
     *   "alias":       "My Inbound TLS Group",
     *   "description": "Optional description"
     * }
     * }</pre>
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createGroup(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody Map<String, Object> request,
            Authentication auth) {
        String actor = resolveActor(userId, auth);
        messagingClient.sendCreateCertGroup(tenantId, actor, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("status", "accepted", "message", "Certificate group creation in progress"));
    }

    // ─── Update group ─────────────────────────────────────────────────────────

    @PutMapping("/{id}")
    public Map<String, Object> updateGroup(
            @PathVariable UUID id,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody Map<String, Object> request,
            Authentication auth) {
        String actor = resolveActor(userId, auth);
        messagingClient.sendUpdateCertGroup(id, tenantId, actor, request);
        return Map.of("status", "accepted", "message", "Certificate group update in progress");
    }

    // ─── Archive group ────────────────────────────────────────────────────────

    @PostMapping("/{id}/archive")
    public Map<String, Object> archiveGroup(
            @PathVariable UUID id,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            Authentication auth) {
        String actor = resolveActor(userId, auth);
        messagingClient.sendArchiveCertGroup(id, tenantId, actor);
        return Map.of("status", "accepted", "message", "Certificate group archival in progress");
    }

    // ─── Delete group ─────────────────────────────────────────────────────────

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> deleteGroup(
            @PathVariable UUID id,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            Authentication auth) {
        String actor = resolveActor(userId, auth);
        messagingClient.sendDeleteCertGroup(id, tenantId, actor);
        return Map.of("status", "accepted", "message", "Certificate group deletion in progress");
    }

    // ─── Group members ────────────────────────────────────────────────────────

    @GetMapping("/{id}/members")
    public Object listGroupMembers(
            @PathVariable UUID id,
            @RequestHeader("X-Tenant-Id") UUID tenantId) {
        return messagingClient.listCertGroupMembers(id, tenantId);
    }

    /**
     * Add an existing vault certificate to this group.
     *
     * <p>Request body:
     * <pre>{@code
     * {
     *   "certId":      "<uuid>",
     *   "memberAlias": "primary"   // optional, short label within the group
     * }
     * }</pre>
     */
    @PostMapping("/{id}/members")
    public ResponseEntity<Map<String, Object>> addMemberToGroup(
            @PathVariable UUID id,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody Map<String, Object> request,
            Authentication auth) {
        String actor = resolveActor(userId, auth);
        messagingClient.sendAddCertToGroup(id, tenantId, actor, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("status", "accepted", "message", "Certificate group member addition in progress"));
    }

    /**
     * Remove a certificate from this group.
     * The certificate becomes standalone and retains its own {@code gatewayTlsLogicalId} if any.
     */
    @DeleteMapping("/{id}/members/{certId}")
    public Map<String, Object> removeMemberFromGroup(
            @PathVariable UUID id,
            @PathVariable UUID certId,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            Authentication auth) {
        String actor = resolveActor(userId, auth);
        messagingClient.sendRemoveCertFromGroup(id, certId, tenantId, actor);
        return Map.of("status", "accepted", "message", "Certificate group member removal in progress");
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private String resolveActor(String userId, Authentication auth) {
        if (userId != null && !userId.isBlank()) return userId;
        return auth != null ? auth.getName() : "system";
    }
}

