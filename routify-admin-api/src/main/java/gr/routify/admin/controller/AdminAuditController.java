package gr.routify.admin.controller;

import gr.routify.admin.client.AuditMessagingClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Admin Audit Controller — dashboard read-only access to audit logs and request logs.
 *
 * <p>All queries go via RabbitMQ to routify-audit-service.
 * Audit data is immutable — no write operations.
 */
@RestController
@RequestMapping("/api/v1/admin/audit")
@RequiredArgsConstructor
public class AdminAuditController {

    private final AuditMessagingClient messagingClient;

    @GetMapping("/events")
    public Map<String, Object> listAuditEvents(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String aggregateType,
            @RequestParam(required = false) String aggregateId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return messagingClient.queryAuditEvents(
                tenantId, eventType, aggregateType, aggregateId, from, to, page, size);
    }

    @GetMapping("/requests")
    public Map<String, Object> listRequestLogs(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestParam(required = false) UUID routeId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return messagingClient.queryRequestLogs(tenantId, routeId, from, to, page, size);
    }

    @GetMapping("/requests/stats/{routeId}")
    public Map<String, Object> getRouteRequestStats(
            @PathVariable UUID routeId,
            @RequestHeader("X-Tenant-Id") UUID tenantId) {
        return messagingClient.getRequestStats(tenantId, routeId);
    }

    @GetMapping("/events/route/{routeId}")
    public Map<String, Object> getRouteHistory(
            @PathVariable UUID routeId,
            @RequestHeader("X-Tenant-Id") UUID tenantId) {
        return messagingClient.queryAuditEvents(
                tenantId, null, "ROUTE", routeId.toString(), null, null, 0, 100);
    }

    @GetMapping("/events/filter/{filterId}")
    public Map<String, Object> getFilterHistory(
            @PathVariable UUID filterId,
            @RequestHeader("X-Tenant-Id") UUID tenantId) {
        return messagingClient.queryAuditEvents(
                tenantId, null, "FILTER", filterId.toString(), null, null, 0, 100);
    }
}

