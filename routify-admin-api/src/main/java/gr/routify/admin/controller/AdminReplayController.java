package gr.routify.admin.controller;

import gr.routify.admin.client.AuditMessagingClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
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
    public Map<String, Object> listFailed(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestParam(required = false) UUID routeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return messagingClient.queryReplayFailed(tenantId, routeId, page, size);
    }

    @GetMapping("/pending")
    public Map<String, Object> listPending(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestParam(required = false) UUID routeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return messagingClient.queryReplayPending(tenantId, routeId, page, size);
    }

    @GetMapping("/stats")
    public Map<String, Object> getStats(@RequestHeader("X-Tenant-Id") UUID tenantId) {
        return messagingClient.getReplayStats(tenantId);
    }

    @PostMapping("/{id}")
    public Map<String, Object> replaySingle(
            @PathVariable UUID id,
            @RequestHeader("X-Tenant-Id") UUID tenantId) {
        return messagingClient.replaySingle(id, tenantId);
    }

    @PostMapping("/bulk")
    public Map<String, Object> replayBulk(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestParam(defaultValue = "50") int limit) {
        return messagingClient.replayBulk(tenantId, limit);
    }
}
