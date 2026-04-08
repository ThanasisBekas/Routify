package io.routify.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for gateway cluster awareness.
 *
 * <p>Prefix: {@code routify.gateway.cluster}
 *
 * @param heartbeatIntervalMs Interval between Redis heartbeats (default 10 000 ms = 10 s).
 *                            Each heartbeat writes instance metadata to Redis and refreshes
 *                            the TTL so that stale instances auto-expire after 30 s.
 */
@ConfigurationProperties(prefix = "routify.gateway.cluster")
public record GatewayClusterConfig(
        long heartbeatIntervalMs
) {
    /** Default heartbeat interval: 10 seconds. */
    private static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 10_000L;

    public GatewayClusterConfig {
        if (heartbeatIntervalMs <= 0) {
            heartbeatIntervalMs = DEFAULT_HEARTBEAT_INTERVAL_MS;
        }
    }

    public GatewayClusterConfig() {
        this(DEFAULT_HEARTBEAT_INTERVAL_MS);
    }
}

