package io.routify.admin.controller;

import io.routify.admin.client.IdentityMessagingClient;
import io.routify.admin.dto.CreateApiKeyRequest;
import io.routify.common.event.QueryResponse;
import io.routify.common.web.RoutifyHeaders;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Admin API Keys Controller — full lifecycle management for API keys.
 *
 * <p>Create and rotate are <b>synchronous RPC</b> (not async Kafka) because
 * the raw key must be returned to the caller exactly once.
 *
 * <p>Authorization: SUPER_ADMIN and TENANT_ADMIN can create/revoke/rotate.
 * All authenticated users can list and view.
 */
@RestController
@RequestMapping("/api/v1/admin/api-keys")
@RequiredArgsConstructor
public class AdminApiKeysController {

    private final IdentityMessagingClient messagingClient;

    @GetMapping
    @PreAuthorize("hasAuthority('API_KEYS_READ') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<QueryResponse.ApiKeysPage> listApiKeys(
            @RequestHeader(value = RoutifyHeaders.TENANT_ID, required = false) UUID tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(messagingClient.queryApiKeys(tenantId, page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('API_KEYS_READ') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<QueryResponse.ApiKeyDetail> getApiKey(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId) {
        return ResponseEntity.ok(messagingClient.getApiKey(id, tenantId));
    }

    /**
     * Create a new API key — returns the raw key exactly once.
     * The raw key is never stored or retrievable again.
     */
    @PostMapping
    @PreAuthorize("hasAuthority('API_KEYS_ADMIN') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<QueryResponse.ApiKeyCreated> createApiKey(
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @Valid @RequestBody CreateApiKeyRequest request,
            Authentication auth) {
        String actor = RoutifyHeaders.resolveActor(null, auth != null ? auth.getName() : null);
        UUID userId = auth != null ? UUID.fromString(auth.getName()) : null;
        String role = request.role() != null ? request.role().toUpperCase() : "OPERATOR";
        QueryResponse.ApiKeyCreated result = messagingClient.createApiKey(
                tenantId, userId, request.name(), role, request.email(),
                request.expiresAt(), actor);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{id}/revoke")
    @PreAuthorize("hasAuthority('API_KEYS_ADMIN') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<QueryResponse.ApiKeyDetail> revokeApiKey(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            Authentication auth) {
        String actor = RoutifyHeaders.resolveActor(null, auth != null ? auth.getName() : null);
        return ResponseEntity.ok(messagingClient.revokeApiKey(id, tenantId, actor));
    }

    /**
     * Rotate an API key — revokes the old key and returns a new raw key.
     */
    @PostMapping("/{id}/rotate")
    @PreAuthorize("hasAuthority('API_KEYS_ADMIN') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<QueryResponse.ApiKeyCreated> rotateApiKey(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            Authentication auth) {
        String actor = RoutifyHeaders.resolveActor(null, auth != null ? auth.getName() : null);
        return ResponseEntity.ok(messagingClient.rotateApiKey(id, tenantId, actor));
    }
}

