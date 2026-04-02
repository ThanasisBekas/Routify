package gr.routify.admin.controller;

import gr.routify.admin.sse.DashboardEventBroadcaster;
import gr.routify.admin.service.DashboardStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;

/**
 * Admin dashboard controller — aggregated stats and real-time events.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final DashboardStatsService statsService;
    private final DashboardEventBroadcaster broadcaster;

    /**
     * Dashboard overview statistics — routes, filters, users, gateway status.
     */
    @GetMapping("/stats")
    public Map<String, Object> getStats(
            @RequestHeader("X-Tenant-Id") UUID tenantId) {
        return statsService.getDashboardStats(tenantId);
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
    public Map<String, Object> getGatewayStatus(
            @RequestHeader("X-Tenant-Id") UUID tenantId) {
        return statsService.getGatewayStatus(tenantId);
    }
}
