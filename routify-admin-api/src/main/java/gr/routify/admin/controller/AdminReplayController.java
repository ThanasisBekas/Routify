package gr.routify.admin.controller;

import gr.routify.admin.client.AuditMessagingClient;
import gr.routify.common.event.QueryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Admin Replay Controller — exposes replay operations to the dashboard.
 *
 * <p>All replay queries and commands are routed via RabbitMQ to routify-audit-service,
 * following the same request/reply pattern used by every other admin-api controller.
 * No direct HTTP calls are made between services.
 */
@RestController
@RequestMapping("/api/v1/admin/audit/replay")
@RequiredArgsConstructor
public class AdminReplayController {

    private final AuditMessagingClient messagingClient;

    @GetMapping("/failed")
    public ResponseEntity<QueryResponse.RequestLogsPage> listFailed(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestParam(required = false) UUID routeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(messagingClient.queryReplayFailed(tenantId, routeId, page, size));
    }

    @GetMapping("/pending")
    public ResponseEntity<QueryResponse.RequestLogsPage> listPending(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestParam(required = false) UUID routeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(messagingClient.queryReplayPending(tenantId, routeId, page, size));
    }

    @GetMapping("/stats")
    public ResponseEntity<QueryResponse.ReplayStatsResult> getStats(
            @RequestHeader("X-Tenant-Id") UUID tenantId) {
        return ResponseEntity.ok(messagingClient.getReplayStats(tenantId));
    }

    @PostMapping("/{id}")
    public ResponseEntity<QueryResponse.ReplaySingleResult> replaySingle(
            @PathVariable UUID id,
            @RequestHeader("X-Tenant-Id") UUID tenantId) {
        return ResponseEntity.status(HttpStatus.OK)
                .body(messagingClient.replaySingle(id, tenantId));
    }

    @PostMapping("/bulk")
    public ResponseEntity<QueryResponse.ReplayBulkResult> replayBulk(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(messagingClient.replayBulk(tenantId, limit));
    }
}
