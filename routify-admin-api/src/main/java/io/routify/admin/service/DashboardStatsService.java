package io.routify.admin.service;

import io.routify.admin.client.AuditMessagingClient;
import io.routify.admin.client.RouteServiceClient;
import io.routify.admin.dto.FleetStatusResponse;
import io.routify.admin.dto.FleetStatusResponse.InstanceStatus;
import io.routify.admin.gateway.service.GatewayActuatorClient;
import io.routify.common.event.QueryResponse;
import io.routify.common.security.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

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
    private final StringRedisTemplate redisTemplate;

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

    // ─── Multi-Gateway Fleet Status ───────────────────────────────────────────

    /**
     * Queries Redis for all registered gateway instances and computes fleet-wide status.
     *
     * <p>Steps:
     * <ol>
     *   <li>Read the {@code routify:gateway:instances} Set to get all instance IDs.</li>
     *   <li>For each instance, HGETALL the heartbeat Hash.</li>
     *   <li>Read the global {@code routify:gateway:config-version}.</li>
     *   <li>Clean up stale entries (instances in Set whose Hash has expired).</li>
     *   <li>Compute per-instance status: HEALTHY / STALE / UNRESPONSIVE.</li>
     * </ol>
     */
    public FleetStatusResponse getFleetStatus() {
        // 1. Read global config version
        String globalVersionStr = redisTemplate.opsForValue().get(RedisKeys.GATEWAY_CONFIG_VERSION);
        long globalConfigVersion = globalVersionStr != null ? Long.parseLong(globalVersionStr) : 0L;

        // 2. Get all registered instance IDs
        Set<String> instanceIds = redisTemplate.opsForSet().members(RedisKeys.GATEWAY_INSTANCES_SET);
        if (instanceIds == null || instanceIds.isEmpty()) {
            return new FleetStatusResponse(globalConfigVersion, 0, 0, 0, List.of());
        }

        List<InstanceStatus> instances = new ArrayList<>();
        List<String> expiredIds = new ArrayList<>();

        for (String instanceId : instanceIds) {
            String key = RedisKeys.GATEWAY_INSTANCES_PREFIX + instanceId;
            Map<Object, Object> hash = redisTemplate.opsForHash().entries(key);

            if (hash == null || hash.isEmpty()) {
                // Heartbeat hash expired — instance is unresponsive; clean from Set
                expiredIds.add(instanceId);
                continue;
            }

            String hostname = getStr(hash, "hostname", "unknown");
            long configVersion = getLong(hash, "configVersion");
            int routeCount = getInt(hash, "routeCount");
            int filterCount = getInt(hash, "filterCount");
            String startedAt = getStr(hash, "startedAt", "");
            String lastReloadAt = getStr(hash, "lastReloadAt", "");
            String lastHeartbeatAt = getStr(hash, "lastHeartbeatAt", "");

            // Compute uptime
            double uptimeHours = 0.0;
            if (!startedAt.isBlank()) {
                try {
                    uptimeHours = Duration.between(Instant.parse(startedAt), Instant.now()).toMinutes() / 60.0;
                    uptimeHours = Math.round(uptimeHours * 10.0) / 10.0;
                } catch (Exception e) {
                    log.trace("Failed to parse startedAt for {}: {}", instanceId, e.getMessage());
                }
            }

            // Compute status
            String status;
            if (configVersion >= globalConfigVersion) {
                status = "HEALTHY";
            } else {
                status = "STALE";
            }

            instances.add(new InstanceStatus(
                    instanceId, hostname, configVersion, routeCount, filterCount,
                    status, startedAt, lastReloadAt, lastHeartbeatAt, uptimeHours
            ));
        }

        // 3. Clean up expired instances
        for (String expiredId : expiredIds) {
            log.info("Removing expired gateway instance from registry: {}", expiredId);
            redisTemplate.opsForSet().remove(RedisKeys.GATEWAY_INSTANCES_SET, expiredId);
        }

        int healthyCount = (int) instances.stream().filter(i -> "HEALTHY".equals(i.status())).count();
        int staleCount = (int) instances.stream().filter(i -> "STALE".equals(i.status())).count();

        return new FleetStatusResponse(
                globalConfigVersion,
                instances.size(),
                healthyCount,
                staleCount,
                instances
        );
    }

    private static String getStr(Map<Object, Object> hash, String key, String defaultVal) {
        Object v = hash.get(key);
        return v != null ? v.toString() : defaultVal;
    }

    private static long getLong(Map<Object, Object> hash, String key) {
        Object v = hash.get(key);
        if (v == null) return 0L;
        try { return Long.parseLong(v.toString()); } catch (NumberFormatException e) { return 0L; }
    }

    private static int getInt(Map<Object, Object> hash, String key) {
        Object v = hash.get(key);
        if (v == null) return 0;
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return 0; }
    }
}
