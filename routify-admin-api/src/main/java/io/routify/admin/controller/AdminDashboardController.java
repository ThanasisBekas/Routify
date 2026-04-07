package io.routify.admin.controller;

import io.routify.admin.client.RouteServiceClient;
import io.routify.admin.dto.FleetStatusResponse;
import io.routify.admin.sse.DashboardEventBroadcaster;
import io.routify.admin.service.DashboardStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;

/**
 * Admin dashboard controller — aggregated stats and real-time events.
 *
 * <p>Authorization: any authenticated user (VIEWER and above) — read-only.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class AdminDashboardController {

    private final DashboardStatsService statsService;
    private final DashboardEventBroadcaster broadcaster;
    private final RouteServiceClient routeServiceClient;

    /**
     * Dashboard overview statistics — routes, filters, users, gateway status.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats(
            @RequestHeader("X-Tenant-Id") UUID tenantId) {
        return ResponseEntity.ok(statsService.getDashboardStats(tenantId));
    }

    /**
     * Server-Sent Events stream for real-time dashboard updates.
     * The dashboard subscribes once and receives push notifications for all changes.
     *
     * <p>Event types pushed:
     * <ul>
     *   <li>{@code route.created} / {@code route.activated} / {@code route.deactivated}</li>
     *   <li>{@code filter.created} / {@code filter.updated} / {@code filter.deleted}</li>
     *   <li>{@code gateway.reloaded} — when the gateway hot-reloads routes</li>
     * </ul>
     */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeToEvents() {
        return broadcaster.subscribe();
    }

    @GetMapping("/dashboard/gateway-status")
    public ResponseEntity<Map<String, Object>> getGatewayStatus(
            @RequestHeader("X-Tenant-Id") UUID tenantId) {
        return ResponseEntity.ok(statsService.getGatewayStatus(tenantId));
    }

    // ─── Gateway Health Dashboard v2 ──────────────────────────────────────────

    /**
     * Per-route health stats (latency percentiles, error rates, status codes).
     *
     * @param window Time window: "1h", "24h", or "7d" (default "24h")
     */
    @GetMapping("/dashboard/route-health")
    public ResponseEntity<?> getRouteHealth(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestParam(defaultValue = "24h") String window) {
        return ResponseEntity.ok(statsService.getRouteHealthSummary(tenantId, window));
    }

    /**
     * SLO status and error budget for a specific route.
     */
    @GetMapping("/routes/{id}/slo-status")
    public ResponseEntity<Map<String, Object>> getRouteSloStatus(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(statsService.getRouteSloStatus(tenantId, id));
    }

    /**
     * Save SLO configuration for a route.
     */
    @PutMapping("/routes/{id}/slo")
    @PreAuthorize("hasAnyRole('OPERATOR', 'TENANT_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<?> saveRouteSlo(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body) {
        double availabilityTarget    = body.containsKey("availabilityTarget")
                ? ((Number) body.get("availabilityTarget")).doubleValue() : 99.9;
        int latencyP99TargetMs       = body.containsKey("latencyP99TargetMs")
                ? ((Number) body.get("latencyP99TargetMs")).intValue() : 1000;
        int evaluationWindowHours    = body.containsKey("evaluationWindowHours")
                ? ((Number) body.get("evaluationWindowHours")).intValue() : 168;

        var result = routeServiceClient.saveRouteSlo(id, tenantId,
                availabilityTarget, latencyP99TargetMs, evaluationWindowHours);
        return ResponseEntity.ok(result);
    }

    // ─── Multi-Gateway Fleet Status ──────────────────────────────────────────

    /**
     * Returns cluster-wide fleet status for all registered gateway instances.
     * Includes per-instance config version, route count, health, and uptime.
     */
    @GetMapping("/gateway/fleet")
    public ResponseEntity<FleetStatusResponse> getFleetStatus() {
        return ResponseEntity.ok(statsService.getFleetStatus());
    }
}
