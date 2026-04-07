package io.routify.admin.controller;

import io.routify.admin.service.ExportService;
import io.routify.admin.service.ImportService;
import io.routify.admin.service.ImportService.ImportApplyResult;
import io.routify.admin.service.ImportService.ImportPreviewResponse;
import io.routify.common.dto.export.GatewayExportV1;
import io.routify.common.web.AsyncAcknowledgement;
import io.routify.common.web.RoutifyHeaders;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Admin Export / Import Controller — declarative YAML route configuration management.
 *
 * <p>Provides three endpoints:
 * <ul>
 *   <li>{@code GET /export} — export all routes, filters, and gateway config as YAML or JSON</li>
 *   <li>{@code POST /import/preview} — dry-run import showing what would change</li>
 *   <li>{@code POST /import} — apply the import (creates/updates via Kafka commands)</li>
 * </ul>
 *
 * <p>Authorization: SUPER_ADMIN or TENANT_ADMIN (export + import both modify gateway config).
 *
 * @see ExportService
 * @see ImportService
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/routes")
@RequiredArgsConstructor
public class AdminExportImportController {

    private static final MediaType MEDIA_TYPE_YAML = MediaType.parseMediaType("application/x-yaml");

    private final ExportService exportService;
    private final ImportService importService;

    // ─── Export ────────────────────────────────────────────────────────────────

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('ROUTES_READ') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<String> exportConfig(
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestParam(required = false) String environment,
            @RequestParam(defaultValue = "yaml") String format,
            Authentication auth) {

        String actor = RoutifyHeaders.resolveActor(null, auth != null ? auth.getName() : null);
        GatewayExportV1 export = exportService.buildExport(tenantId, environment, actor);

        String filename = "routify-export-" + tenantId.toString().substring(0, 8)
                + "-" + LocalDate.now() + ("json".equalsIgnoreCase(format) ? ".json" : ".yaml");

        String body;
        MediaType contentType;
        if ("json".equalsIgnoreCase(format)) {
            body = exportService.toJson(export);
            contentType = MediaType.APPLICATION_JSON;
        } else {
            body = exportService.toYaml(export);
            contentType = MEDIA_TYPE_YAML;
        }

        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(body);
    }

    // ─── Import Preview ───────────────────────────────────────────────────────

    @PostMapping("/import/preview")
    @PreAuthorize("hasAuthority('ROUTES_WRITE') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<ImportPreviewResponse> importPreview(
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestBody String yamlBody) {

        ImportPreviewResponse preview = importService.preview(tenantId, yamlBody);
        return ResponseEntity.ok(preview);
    }

    // ─── Import Apply ─────────────────────────────────────────────────────────

    @PostMapping("/import")
    @PreAuthorize("hasAuthority('ROUTES_WRITE') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<AsyncAcknowledgement> importApply(
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestHeader(value = RoutifyHeaders.USER_ID, required = false) String userId,
            @RequestBody String yamlBody,
            Authentication auth) {

        String actor = RoutifyHeaders.resolveActor(userId, auth != null ? auth.getName() : null);
        ImportApplyResult result = importService.apply(tenantId, actor, yamlBody);

        String message = String.format(
                "Import in progress — filters: %d created, %d updated; routes: %d created, %d updated",
                result.filtersCreated(), result.filtersUpdated(),
                result.routesCreated(), result.routesUpdated()
        );

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of(message));
    }
}

