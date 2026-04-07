package io.routify.admin.service;

import io.routify.admin.client.AuditMessagingClient;
import io.routify.admin.client.RouteServiceClient;
import io.routify.admin.gateway.service.GatewayActuatorClient;
import io.routify.common.event.QueryResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Aggregates statistics from multiple services for the dashboard overview.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardStatsService {

    private final RouteServiceClient routeServiceClient;
    private final GatewayActuatorClient gatewayActuatorClient;
    private final AuditMessagingClient auditMessagingClient;

    public Map<String, Object> getDashboardStats(UUID tenantId) {
        Map<String, Object> stats = new HashMap<>();
        try {
            QueryResponse.RouteStatsResult routeStats = routeServiceClient.getRouteStats(tenantId);
            stats.put("routes", routeStats);
        } catch (Exception e) {
            log.warn("Failed to fetch route stats: {}", e.getMessage());
            stats.put("routes", Map.of("error", "unavailable"));
        }
        stats.put("tenantId", tenantId);
        return stats;
    }

    public Map<String, Object> getGatewayStatus(UUID tenantId) {
        Map<String, Object> status = new HashMap<>();
        try {
            var health  = gatewayActuatorClient.getHealth();
            var routes  = gatewayActuatorClient.getLiveRoutes();
            var cbs     = gatewayActuatorClient.getCircuitBreakerStates();
            status.put("health",          health);
            status.put("routes",          routes);
            status.put("circuitBreakers", cbs);
        } catch (Exception e) {
            log.warn("Failed to fetch gateway status: {}", e.getMessage());
            status.put("gateway", Map.of("status", "unknown"));
        }
        return status;
    }

    // ─── Gateway Health Dashboard v2 ──────────────────────────────────────────

    /**
     * Fetches per-route health summary (latency, error rates, status codes) from audit-service.
     */
    public QueryResponse.RouteHealthResponse getRouteHealthSummary(UUID tenantId, String window) {
        return auditMessagingClient.queryRouteHealth(tenantId, window);
    }

    /**
     * Computes the SLO status and error budget for a specific route.
     */
    public Map<String, Object> getRouteSloStatus(UUID tenantId, UUID routeId) {
        // 1. Fetch SLO config from route-service
        var sloConfig = routeServiceClient.getRouteSlo(routeId, tenantId);

        // 2. Fetch actual metrics from audit-service scoped to the SLO evaluation window
        int windowHours = sloConfig.evaluationWindowHours();
        String window;
        if (windowHours <= 1) {
            window = "1h";
        } else if (windowHours <= 24) {
            window = "24h";
        } else {
            window = "7d";
        }
        var healthResponse = auditMessagingClient.queryRouteHealth(tenantId, window);

        // Find the specific route's health entry
        var routeHealth = healthResponse.routes().stream()
                .filter(r -> routeId.equals(r.routeId()))
                .findFirst()
                .orElse(null);

        long   totalRequests = routeHealth != null ? routeHealth.totalRequests() : 0L;
        long   errorCount    = routeHealth != null ? routeHealth.errorCount() : 0L;
        double p99LatencyMs  = routeHealth != null ? routeHealth.p99LatencyMs() : 0.0;
        double actualAvailability = totalRequests > 0
                ? (1.0 - (double) errorCount / totalRequests) * 100.0
                : 100.0;

        // 3. Compute error budget
        double allowedErrors   = totalRequests * (1.0 - sloConfig.availabilityTarget() / 100.0);
        double consumed        = errorCount;
        double remaining       = allowedErrors - consumed;
        double percentConsumed = allowedErrors > 0 ? (consumed / allowedErrors) * 100.0 : 0.0;

        Map<String, Object> result = new HashMap<>();
        result.put("routeId", routeId);
        result.put("slo", Map.of(
                "availabilityTarget", sloConfig.availabilityTarget(),
                "latencyP99TargetMs", sloConfig.latencyP99TargetMs(),
                "windowHours", sloConfig.evaluationWindowHours(),
                "configured", sloConfig.found()
        ));
        result.put("actual", Map.of(
                "availability", Math.round(actualAvailability * 100.0) / 100.0,
                "latencyP99Ms", p99LatencyMs,
                "totalRequests", totalRequests,
                "errorCount", errorCount
        ));
        result.put("errorBudget", Map.of(
                "totalBudget", Math.round(allowedErrors * 10.0) / 10.0,
                "consumed", consumed,
                "remaining", Math.round(remaining * 10.0) / 10.0,
                "percentConsumed", Math.round(percentConsumed * 10.0) / 10.0
        ));
        result.put("latencySloMet", p99LatencyMs <= sloConfig.latencyP99TargetMs());
        result.put("availabilitySloMet", actualAvailability >= sloConfig.availabilityTarget());
        return result;
    }
}
