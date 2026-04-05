package gr.routify.admin.controller;

import gr.routify.admin.client.CertVaultMessagingClient;
import gr.routify.admin.dto.UploadCertificateRequest;
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
 * Admin Certificate Vault Controller — dashboard management of inbound TLS certificates.
 *
 * <p>All read operations are served via RabbitMQ request/reply to routify-cert-vault.
 * All write operations (upload, revoke, delete) are published as Kafka command events.
 * There are NO direct HTTP calls to cert-vault.
 *
 * <p>Authorization:
 * <ul>
 *   <li>Read (GET): any authenticated user (VIEWER and above)</li>
 *   <li>Write (POST/DELETE): OPERATOR, TENANT_ADMIN, or SUPER_ADMIN</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/certificates")
@RequiredArgsConstructor
public class AdminCertificatesController {

    private final CertVaultMessagingClient messagingClient;

    // ─── List certificates ────────────────────────────────────────────────────

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<QueryResponse.CertsPage> listCertificates(
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDir) {
        return ResponseEntity.ok(
                messagingClient.queryCertificates(tenantId, status, page, size, sortBy, sortDir));
    }

    // ─── Get single certificate ────────────────────────────────────────────────

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<QueryResponse.CertDetail> getCertificate(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId) {
        return ResponseEntity.ok(messagingClient.getCertificate(id, tenantId));
    }

    // ─── Vault statistics ─────────────────────────────────────────────────────

    @GetMapping("/stats")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<QueryResponse.CertStatsResult> getStats(
            @RequestHeader(value = RoutifyHeaders.TENANT_ID, required = false) UUID tenantId) {
        return ResponseEntity.ok(messagingClient.getCertVaultStats(tenantId));
    }

    // ─── Upload certificate (into a group) ────────────────────────────────────

    /**
     * Upload a new inbound TLS certificate to the vault, assigning it to an existing group.
     *
     * <p>A certificate group must exist before uploading. The group's {@code logicalId}
     * is the stable gateway TLS registry key — no individual certificate mapping needed.
     *
     * <p>Expected request body:
     * <pre>{@code
     * {
     *   "groupId":     "<uuid>",                  // mandatory — group to assign this cert to
     *   "memberAlias": "primary",                  // optional short label within the group
     *   "alias":       "My API Certificate",       // display name
     *   "description": "Optional description",
     *   "format":      "PEM",                      // PEM | PKCS12
     *   "certPem":     "-----BEGIN CERTIFICATE-----\n...",
     *   "privateKey":  "-----BEGIN PRIVATE KEY-----\n..."  // optional
     * }
     * }</pre>
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
    public ResponseEntity<AsyncAcknowledgement> uploadCertificate(
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestHeader(value = RoutifyHeaders.USER_ID, required = false) String userId,
            @RequestBody UploadCertificateRequest request,
            Authentication auth) {
        String actor = RoutifyHeaders.resolveActor(userId, auth != null ? auth.getName() : null);
        messagingClient.sendUploadCertificate(tenantId, actor, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("Certificate upload in progress"));
    }

    // ─── Revoke certificate ────────────────────────────────────────────────────

    @PostMapping("/{id}/revoke")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
    public ResponseEntity<AsyncAcknowledgement> revokeCertificate(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestHeader(value = RoutifyHeaders.USER_ID, required = false) String userId,
            Authentication auth) {
        String actor = RoutifyHeaders.resolveActor(userId, auth != null ? auth.getName() : null);
        messagingClient.sendRevokeCertificate(id, tenantId, actor);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("Certificate revocation in progress"));
    }

    // ─── Delete certificate ────────────────────────────────────────────────────

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
    public ResponseEntity<AsyncAcknowledgement> deleteCertificate(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestHeader(value = RoutifyHeaders.USER_ID, required = false) String userId,
            Authentication auth) {
        String actor = RoutifyHeaders.resolveActor(userId, auth != null ? auth.getName() : null);
        messagingClient.sendDeleteCertificate(id, tenantId, actor);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("Certificate deletion in progress"));
    }
}
