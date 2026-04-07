package io.routify.admin.dto;

import java.util.List;

/**
 * Response for the fleet status endpoint ({@code GET /api/v1/admin/gateway/fleet}).
 *
 * <p>Provides a cluster-wide view of all registered gateway instances,
 * their config versions, and health status.
 *
 * @param globalConfigVersion the shared monotonic config version from Redis
 * @param instanceCount       total number of registered gateway instances
 * @param healthyCount        instances whose config version matches the global version
 * @param staleCount          instances whose config version is behind the global version
 * @param instances           per-instance status details
 */
public record FleetStatusResponse(
        long globalConfigVersion,
        int instanceCount,
        int healthyCount,
        int staleCount,
        List<InstanceStatus> instances
) {

    /**
     * Status of an individual gateway instance in the fleet.
     *
     * @param instanceId      unique identifier (hostname:port:uuid8)
     * @param hostname        OS hostname of the instance
     * @param configVersion   local config version counter
     * @param routeCount      number of active route definitions loaded
     * @param filterCount     number of filter definitions loaded
     * @param status          computed health status: HEALTHY, STALE, or UNRESPONSIVE
     * @param startedAt       ISO-8601 instant when the instance started
     * @param lastReloadAt    ISO-8601 instant of the most recent route reload
     * @param lastHeartbeatAt ISO-8601 instant of the last heartbeat
     * @param uptimeHours     hours since the instance started
     */
    public record InstanceStatus(
            String instanceId,
            String hostname,
            long configVersion,
            int routeCount,
            int filterCount,
            String status,
            String startedAt,
            String lastReloadAt,
            String lastHeartbeatAt,
            double uptimeHours
    ) {}
}

