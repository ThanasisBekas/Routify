package io.routify.admin.controller;

import io.routify.admin.client.AuditMessagingClient;
import io.routify.common.event.QueryResponse;
import io.routify.common.web.RoutifyHeaders;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Admin controller for AI prompt version management.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET  /api/v1/admin/ai-filter/{filterId}/versions}                       — list versions</li>
 *   <li>{@code GET  /api/v1/admin/ai-filter/{filterId}/versions/{versionId}}            — version detail</li>
 *   <li>{@code POST /api/v1/admin/ai-filter/{filterId}/versions}                        — create draft</li>
 *   <li>{@code POST /api/v1/admin/ai-filter/{filterId}/versions/{versionId}/activate}   — activate</li>
 *   <li>{@code POST /api/v1/admin/ai-filter/{filterId}/versions/{versionId}/archive}    — archive</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/ai-filter/{filterId}/versions")
@RequiredArgsConstructor
public class AdminAiPromptVersionController {

    private final AuditMessagingClient auditMessagingClient;

    @GetMapping
    @PreAuthorize("hasAuthority('AI_POLICY_READ') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<QueryResponse.PromptVersionsPage> listVersions(
            @PathVariable UUID filterId,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.debug("List prompt versions: filterId={} page={} size={}", filterId, page, size);
        return ResponseEntity.ok(auditMessagingClient.queryPromptVersions(filterId, tenantId, page, size));
    }

    @GetMapping("/{versionId}")
    @PreAuthorize("hasAuthority('AI_POLICY_READ') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<QueryResponse.PromptVersionDetail> getVersion(
            @PathVariable UUID filterId,
            @PathVariable UUID versionId,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId) {
        log.debug("Get prompt version: filterId={} versionId={}", filterId, versionId);
        return ResponseEntity.ok(auditMessagingClient.getPromptVersion(versionId, tenantId));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('AI_POLICY_WRITE') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
    public ResponseEntity<QueryResponse.PromptVersionDetail> createDraft(
            @PathVariable UUID filterId,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestHeader(value = RoutifyHeaders.USER_ID, required = false) String userId,
            @RequestBody CreateDraftRequest body) {
        log.debug("Create draft prompt version: filterId={}", filterId);
        String actor = RoutifyHeaders.resolveActor(userId, null);
        var result = auditMessagingClient.savePromptVersion(
                filterId, tenantId, null,
                body.promptText(), body.description(), "CREATE_DRAFT", actor);
        return ResponseEntity.status(201).body(result);
    }

    @PostMapping("/{versionId}/activate")
    @PreAuthorize("hasAuthority('AI_POLICY_WRITE') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
    public ResponseEntity<QueryResponse.PromptVersionDetail> activate(
            @PathVariable UUID filterId,
            @PathVariable UUID versionId,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestHeader(value = RoutifyHeaders.USER_ID, required = false) String userId) {
        log.debug("Activate prompt version: filterId={} versionId={}", filterId, versionId);
        String actor = RoutifyHeaders.resolveActor(userId, null);
        return ResponseEntity.ok(auditMessagingClient.savePromptVersion(
                filterId, tenantId, versionId, null, null, "ACTIVATE", actor));
    }

    @PostMapping("/{versionId}/archive")
    @PreAuthorize("hasAuthority('AI_POLICY_WRITE') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
    public ResponseEntity<QueryResponse.PromptVersionDetail> archive(
            @PathVariable UUID filterId,
            @PathVariable UUID versionId,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestHeader(value = RoutifyHeaders.USER_ID, required = false) String userId) {
        log.debug("Archive prompt version: filterId={} versionId={}", filterId, versionId);
        String actor = RoutifyHeaders.resolveActor(userId, null);
        return ResponseEntity.ok(auditMessagingClient.savePromptVersion(
                filterId, tenantId, versionId, null, null, "ARCHIVE", actor));
    }

    /** Request body for creating a draft prompt version. */
    public record CreateDraftRequest(String promptText, String description) {}
}

