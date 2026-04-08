package io.routify.admin.controller;

import io.routify.admin.client.CertVaultMessagingClient;
import io.routify.common.event.QueryResponse;
import io.routify.common.web.RoutifyHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Admin ACME Controller — dashboard management of automated certificate lifecycle.
 *
 * <p>All operations are served via RabbitMQ sync RPC to routify-cert-vault.
 * ACME operations require immediate feedback (account URL, challenge status, cert ID)
 * so they use request/reply rather than Kafka commands.
 *
 * <p>Authorization: SUPER_ADMIN or TENANT_ADMIN only.
 */
@RestController
@RequestMapping("/api/v1/admin/certs/acme")
@RequiredArgsConstructor
public class AdminAcmeController {

    private final CertVaultMessagingClient messagingClient;

    // ─── Register ACME account ──────────────────────────────────────────────────

    @PostMapping("/register")
    @PreAuthorize("hasAuthority('CERTS_ADMIN') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<QueryResponse.AcmeAccountResult> registerAccount(
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestBody RegisterAcmeAccountRequest request) {
        return ResponseEntity.ok(
                messagingClient.registerAcmeAccount(tenantId, request.email(), request.provider()));
    }

    // ─── Issue certificate for domain ───────────────────────────────────────────

    @PostMapping("/issue")
    @PreAuthorize("hasAuthority('CERTS_ADMIN') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<QueryResponse.AcmeOrderDetail> issueCertificate(
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestBody IssueAcmeCertificateRequest request) {
        return ResponseEntity.ok(
                messagingClient.issueAcmeCertificate(
                        tenantId, request.accountId(), request.domain(), request.certGroupId()));
    }

    // ─── List ACME orders (paginated) ───────────────────────────────────────────

    @GetMapping("/orders")
    @PreAuthorize("hasAuthority('CERTS_READ') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<QueryResponse.AcmeOrdersPage> listOrders(
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(messagingClient.queryAcmeOrders(tenantId, page, size));
    }

    // ─── Get single ACME order ──────────────────────────────────────────────────

    @GetMapping("/orders/{id}")
    @PreAuthorize("hasAuthority('CERTS_READ') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<QueryResponse.AcmeOrderDetail> getOrder(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId) {
        return ResponseEntity.ok(messagingClient.getAcmeOrder(id, tenantId));
    }

    // ─── Manual renewal trigger ─────────────────────────────────────────────────

    @PostMapping("/orders/{id}/renew")
    @PreAuthorize("hasAuthority('CERTS_ADMIN') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<QueryResponse.AcmeOrderDetail> renewOrder(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId) {
        return ResponseEntity.ok(messagingClient.renewAcmeCertificate(id, tenantId));
    }

    // ─── Request DTOs ───────────────────────────────────────────────────────────

    public record RegisterAcmeAccountRequest(String email, String provider) {}

    public record IssueAcmeCertificateRequest(UUID accountId, String domain, UUID certGroupId) {}
}

