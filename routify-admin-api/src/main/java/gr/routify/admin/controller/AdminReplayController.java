package gr.routify.admin.controller;

import gr.routify.admin.client.AuditMessagingClient;
import gr.routify.common.event.QueryResponse;
import lombok.RequiredArgsConstructor;
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
    public QueryResponse.RequestLogsPage listFailed(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestParam(required = false) UUID routeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return messagingClient.queryReplayFailed(tenantId, routeId, page, size);
    }

    @GetMapping("/pending")
    public QueryResponse.RequestLogsPage listPending(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestParam(required = false) UUID routeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return messagingClient.queryReplayPending(tenantId, routeId, page, size);
    }

    @GetMapping("/stats")
    public QueryResponse.ReplayStatsResult getStats(@RequestHeader("X-Tenant-Id") UUID tenantId) {
        return messagingClient.getReplayStats(tenantId);
    }

    @PostMapping("/{id}")
    public QueryResponse.ReplaySingleResult replaySingle(
            @PathVariable UUID id,
            @RequestHeader("X-Tenant-Id") UUID tenantId) {
        return messagingClient.replaySingle(id, tenantId);
    }

    @PostMapping("/bulk")
    public QueryResponse.ReplayBulkResult replayBulk(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestParam(defaultValue = "50") int limit) {
        return messagingClient.replayBulk(tenantId, limit);
    }
}
