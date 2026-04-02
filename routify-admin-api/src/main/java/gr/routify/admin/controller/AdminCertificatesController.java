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
 * Admin Certificate Vault Controller — dashboard management of inbound TLS certificates.
 *
 * <p>Certificates always belong to a {@code CertGroup}. The group's {@code logicalId}
 * is the stable key used by the gateway TLS registry and authorization filters.
 * Upload requires a {@code groupId}; the certificate's own logical ID is auto-generated.
 *
 * <p>All read operations are served via RabbitMQ request/reply to routify-cert-vault.
 * All write operations (upload, revoke, delete) are published as Kafka command events.
 * There are NO direct HTTP calls to cert-vault.
 *
 * <p>Gateway TLS binding is managed at the group level via
 * {@link AdminCertGroupsController} — not at the individual certificate level.
 */
@RestController
@RequestMapping("/api/v1/admin/certificates")
@RequiredArgsConstructor
public class AdminCertificatesController {

    private final CertVaultMessagingClient messagingClient;

    // ─── List certificates ────────────────────────────────────────────────────

    @GetMapping
    public Map<String, Object> listCertificates(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDir) {
        return messagingClient.queryCertificates(tenantId, status, page, size, sortBy, sortDir);
    }

    // ─── Get single certificate ────────────────────────────────────────────────

    @GetMapping("/{id}")
    public Map<String, Object> getCertificate(
            @PathVariable UUID id,
            @RequestHeader("X-Tenant-Id") UUID tenantId) {
        return messagingClient.getCertificate(id, tenantId);
    }

    // ─── Vault statistics ─────────────────────────────────────────────────────

    @GetMapping("/stats")
    public Map<String, Object> getStats(
            @RequestHeader(value = "X-Tenant-Id", required = false) UUID tenantId) {
        return messagingClient.getCertVaultStats(tenantId);
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
    public ResponseEntity<Map<String, Object>> uploadCertificate(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody Map<String, Object> request,
            Authentication auth) {
        String actor = resolveActor(userId, auth);
        messagingClient.sendCertCommand("UPLOAD_CERTIFICATE", request, tenantId, actor);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("status", "accepted", "message", "Certificate upload in progress"));
    }

    // ─── Revoke certificate ────────────────────────────────────────────────────

    @PostMapping("/{id}/revoke")
    public Map<String, Object> revokeCertificate(
            @PathVariable UUID id,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            Authentication auth) {
        String actor = resolveActor(userId, auth);
        messagingClient.sendCertCommand("REVOKE_CERTIFICATE",
                Map.of("id", id.toString()), tenantId, actor);
        return Map.of("status", "accepted", "message", "Certificate revocation in progress");
    }

    // ─── Delete certificate ────────────────────────────────────────────────────

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> deleteCertificate(
            @PathVariable UUID id,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            Authentication auth) {
        String actor = resolveActor(userId, auth);
        messagingClient.sendCertCommand("DELETE_CERTIFICATE",
                Map.of("id", id.toString()), tenantId, actor);
        return Map.of("status", "accepted", "message", "Certificate deletion in progress");
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private String resolveActor(String userId, Authentication auth) {
        if (userId != null && !userId.isBlank()) return userId;
        return auth != null ? auth.getName() : "system";
    }
}
