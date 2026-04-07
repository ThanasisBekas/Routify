package io.routify.gateway.cluster;

import io.routify.common.security.RedisKeys;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gateway cluster instance registry — heartbeats instance metadata to Redis
 * so that admin-api can build a fleet-wide status view.
 *
 * <p>Each gateway instance:
 * <ol>
 *   <li>Generates a stable {@code instanceId} on startup (hostname:port:uuid8).</li>
 *   <li>Heartbeats every {@code routify.gateway.cluster.heartbeat-interval-ms} (default 10 s)
 *       to a Redis Hash at {@code routify:gateway:instances:<instanceId>} with TTL 30 s.</li>
 *   <li>Adds its ID to the {@code routify:gateway:instances} Redis Set.</li>
 *   <li>On graceful shutdown, removes itself from both the Hash and the Set.</li>
 * </ol>
 *
 * <p>The {@link #incrementConfigVersion(int)} method must be called by
 * {@code DynamicRouteRefreshListener} after each successful route/filter reload.
 * It atomically increments both the local counter and the shared Redis counter.
 */
@Slf4j
@Component
public class GatewayInstanceRegistry {

    private static final Duration HEARTBEAT_TTL = Duration.ofSeconds(30);

    private final ReactiveStringRedisTemplate redisTemplate;

    @Getter
    private final AtomicLong configVersion = new AtomicLong(0);

    @Getter
    private volatile int routeCount;

    @Getter
    private volatile int filterCount;

    @Getter
    private volatile Instant startedAt;

    @Getter
    private volatile Instant lastReloadAt;

    @Getter
    private String instanceId;

    private final String hostname;
    private final int port;

    public GatewayInstanceRegistry(
            ReactiveStringRedisTemplate redisTemplate,
            @Value("${server.port:8080}") int port) {
        this.redisTemplate = redisTemplate;
        this.port = port;
        this.hostname = resolveHostname();
    }

    @PostConstruct
    public void init() {
        this.startedAt = Instant.now();
        this.instanceId = hostname + ":" + port + ":" + UUID.randomUUID().toString().substring(0, 8);
        log.info("Gateway instance registered: instanceId={}", instanceId);
        heartbeat();
    }

    @PreDestroy
    public void deregister() {
        log.info("Gateway instance deregistering: instanceId={}", instanceId);
        String key = RedisKeys.GATEWAY_INSTANCES_PREFIX + instanceId;
        redisTemplate.delete(key).subscribe(
                ok -> log.debug("Removed heartbeat hash for {}", instanceId),
                err -> log.warn("Failed to remove heartbeat hash: {}", err.getMessage())
        );
        redisTemplate.opsForSet().remove(RedisKeys.GATEWAY_INSTANCES_SET, instanceId).subscribe(
                ok -> log.debug("Removed {} from instances set", instanceId),
                err -> log.warn("Failed to remove from instances set: {}", err.getMessage())
        );
    }

    /**
     * Periodic heartbeat — writes instance metadata to Redis Hash with TTL,
     * and ensures the instance is present in the instances Set.
     */
    @Scheduled(fixedDelayString = "${routify.gateway.cluster.heartbeat-interval-ms:10000}")
    public void heartbeat() {
        if (instanceId == null) return; // guard against scheduler running before init

        String key = RedisKeys.GATEWAY_INSTANCES_PREFIX + instanceId;
        Map<String, String> fields = Map.of(
                "hostname", hostname,
                "port", String.valueOf(port),
                "configVersion", String.valueOf(configVersion.get()),
                "routeCount", String.valueOf(routeCount),
                "filterCount", String.valueOf(filterCount),
                "startedAt", startedAt != null ? startedAt.toString() : Instant.now().toString(),
                "lastReloadAt", lastReloadAt != null ? lastReloadAt.toString() : "",
                "lastHeartbeatAt", Instant.now().toString()
        );

        redisTemplate.opsForHash().putAll(key, fields)
                .then(redisTemplate.expire(key, HEARTBEAT_TTL))
                .then(redisTemplate.opsForSet().add(RedisKeys.GATEWAY_INSTANCES_SET, instanceId))
                .subscribe(
                        ok -> log.trace("Heartbeat sent: instanceId={} configVersion={}",
                                instanceId, configVersion.get()),
                        err -> log.warn("Heartbeat failed: {}", err.getMessage())
                );
    }

    /**
     * Increments the local config version and the shared Redis config version counter.
     * Must be called after each successful route/filter/config reload.
     *
     * @param newRouteCount the number of active route definitions after reload
     */
    public void incrementConfigVersion(int newRouteCount) {
        long newVersion = configVersion.incrementAndGet();
        this.routeCount = newRouteCount;
        this.lastReloadAt = Instant.now();

        // Atomically increment the shared global config version in Redis
        redisTemplate.opsForValue().increment(RedisKeys.GATEWAY_CONFIG_VERSION)
                .subscribe(
                        globalVersion -> log.debug("Config version incremented: local={} global={} routes={}",
                                newVersion, globalVersion, newRouteCount),
                        err -> log.warn("Failed to increment global config version: {}", err.getMessage())
                );
    }

    /**
     * Updates the filter count (called after filter-related reloads).
     */
    public void setFilterCount(int count) {
        this.filterCount = count;
    }

    private static String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-host";
        }
    }
}

