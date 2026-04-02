package gr.routify.admin.service;

import gr.routify.admin.client.RouteServiceClient;
import gr.routify.admin.gateway.service.GatewayActuatorClient;
import gr.routify.common.event.QueryResponse;
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
}
