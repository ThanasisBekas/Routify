package io.routify.route.outbox;

import io.routify.route.config.CacheConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Component;

/**
 * Dedicated bean for evicting the gateway snapshot Caffeine cache.
 *
 * <p>Extracted from {@link OutboxPoller} to fix a Spring AOP self-invocation bug:
 * when {@code OutboxPoller.pollAndPublish()} called {@code this.evictGatewaySnapshotCache()},
 * the call bypassed the Spring proxy and the {@code @CacheEvict} annotation was silently
 * ignored — leaving the gateway snapshot cache stale for up to 60 seconds (TTL).
 *
 * <p>By moving the eviction into a separate Spring bean, the proxy correctly intercepts
 * the call and the Caffeine cache is evicted immediately after outbox events are published.
 */
@Slf4j
@Component
public class GatewaySnapshotCacheEvictor {

    /**
     * Evicts the gateway snapshot cache. Called by {@link OutboxPoller#pollAndPublish()}
     * after at least one event has been successfully published to Kafka.
     */
    @CacheEvict(value = CacheConfig.CACHE_GATEWAY_SNAPSHOT, allEntries = true)
    public void evict() {
        log.debug("GatewaySnapshotCacheEvictor: evicted gateway snapshot cache after publishing events");
    }
}

