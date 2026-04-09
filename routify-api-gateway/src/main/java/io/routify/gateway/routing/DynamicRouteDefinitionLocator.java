package io.routify.gateway.routing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.routify.gateway.client.RouteServiceClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dynamic Route Definition Locator — Zero Downtime Hot-Reload.
 *
 * <p>Implements {@link RouteDefinitionLocator} for dynamic route management.
 *
 * <h3>Communication Strategy (Message-Broker-Only)</h3>
 * <ul>
 *   <li><b>Route snapshot fetch</b>: RabbitMQ request/reply via {@link RouteServiceClient}.</li>
 *   <li><b>Reload trigger</b>: Kafka {@code routify.gateway.reload} topic
 *       consumed by {@link DynamicRouteRefreshListener}.</li>
 *   <li><b>Cache</b>: Redis for fast repeated reads within the TTL window.</li>
 * </ul>
 *
 * <h3>Concurrency (Phase 3.4 fix)</h3>
 * <p>Uses an {@link AtomicBoolean} instead of {@code synchronized} to prevent
 * concurrent refreshes. The {@code synchronized} keyword was misleading because
 * the method immediately subscribes to a reactive chain and returns — subsequent
 * calls were not blocked by the in-flight reactive operation.</p>
 *
 * <h3>Cache TTL (Phase 3.5 fix)</h3>
 * <p>TTL reduced from 5 minutes to 60 seconds. A periodic background refresh
 * runs every 2 minutes as a safety net against lost Kafka events.</p>
 */
@Slf4j
@Component
public class DynamicRouteDefinitionLocator implements RouteDefinitionLocator {

    private static final String CACHE_KEY = "routify:gateway:route-snapshot";
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final AtomicBoolean refreshInProgress = new AtomicBoolean(false);
    private final AtomicBoolean pendingForceRefresh = new AtomicBoolean(false);

    private final RouteServiceClient          routeServiceClient;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper                objectMapper;
    private final RouteDefinitionBuilder      routeDefinitionBuilder;
    private final ApplicationEventPublisher   eventPublisher;

    private final AtomicReference<List<RouteDefinition>> currentRoutes =
            new AtomicReference<>(List.of());

    private final Sinks.Many<List<RouteDefinition>> routeSink =
            Sinks.many().replay().latest();

    public DynamicRouteDefinitionLocator(
            RouteServiceClient routeServiceClient,
            ReactiveStringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            RouteDefinitionBuilder routeDefinitionBuilder,
            ApplicationEventPublisher eventPublisher) {
        this.routeServiceClient     = routeServiceClient;
        this.redisTemplate          = redisTemplate;
        this.objectMapper           = objectMapper;
        this.routeDefinitionBuilder = routeDefinitionBuilder;
        this.eventPublisher         = eventPublisher;
    }

    @Override
    public Flux<RouteDefinition> getRouteDefinitions() {
        return Flux.fromIterable(currentRoutes.get());
    }

    /**
     * Triggers a full route reload.
     * Strategy: Redis cache → RabbitMQ route-service fetch → update atomic ref.
     *
     * <p>Phase 3.4 fix: uses {@link AtomicBoolean} to prevent concurrent refreshes.
     * Only the first call proceeds; subsequent calls during an in-flight refresh are skipped.
     *
     * <p>Returns a {@link Mono} that completes when the refresh finishes. Callers that
     * need completion awareness (e.g. startup) can call {@code .block()}. Fire-and-forget
     * callers should use {@link #refreshAsync()}.
     */
    public Mono<Void> refresh() {
        if (!refreshInProgress.compareAndSet(false, true)) {
            log.debug("Route refresh already in progress — skipping duplicate request");
            return Mono.empty();
        }

        // If a force refresh was requested, skip Redis cache for this cycle.
        boolean skipCache = pendingForceRefresh.getAndSet(false);

        log.info("DynamicRouteDefinitionLocator: refreshing route definitions via RabbitMQ (skipCache={})...", skipCache);

        Mono<List<RouteSnapshotDto>> source;
        if (skipCache) {
            // Bypass Redis — go directly to route-service via RabbitMQ
            source = Mono.fromCallable(routeServiceClient::fetchGatewaySnapshotSync)
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnNext(this::cacheToRedis);
        } else {
            source = redisTemplate.opsForValue().get(CACHE_KEY)
                    .onErrorResume(ex -> {
                        log.warn("Redis cache read failed ({}), falling back to route-service fetch: {}",
                                ex.getClass().getSimpleName(), ex.getMessage());
                        return Mono.empty();
                    })
                    .flatMap(cachedJson -> {
                        try {
                            List<RouteSnapshotDto> snapshots = objectMapper.readValue(
                                    cachedJson, new TypeReference<>() {});
                            log.debug("Loaded {} routes from Redis cache", snapshots.size());
                            return Mono.just(snapshots);
                        } catch (Exception e) {
                            log.warn("Failed to deserialize cached routes, fetching from route-service: {}", e.getMessage());
                            return Mono.empty();
                        }
                    })
                    .switchIfEmpty(
                            Mono.fromCallable(routeServiceClient::fetchGatewaySnapshotSync)
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .doOnNext(this::cacheToRedis)
                    );
        }

        return source
                .map(snapshots -> snapshots.stream()
                        .map(routeDefinitionBuilder::build)
                        .toList())
                .doOnNext(definitions -> {
                    currentRoutes.set(definitions);
                    routeSink.tryEmitNext(definitions);
                    log.info("Route table updated: {} active routes loaded", definitions.size());
                    // Notify Spring Cloud Gateway to re-evaluate route definitions.
                    eventPublisher.publishEvent(new RefreshRoutesEvent(this));
                })
                .doOnError(ex -> log.error("Failed to refresh route definitions: {}", ex.getMessage(), ex))
                .doFinally(signal -> {
                    refreshInProgress.set(false);
                    // If a force refresh was requested while we were busy, run another cycle
                    if (pendingForceRefresh.get()) {
                        log.debug("Pending force refresh detected — scheduling follow-up refresh");
                        refreshAsync();
                    }
                })
                .then();
    }

    /**
     * Fire-and-forget variant of {@link #refresh()} for use in Kafka listeners
     * and other contexts where completion awareness is not needed.
     */
    public void refreshAsync() {
        refresh().subscribe(
                null,
                ex -> log.error("Unhandled error in route refresh pipeline: {}", ex.getMessage(), ex)
        );
    }

    /**
     * Forces cache invalidation and re-fetches from route-service via RabbitMQ.
     *
     * <p>Sets {@code pendingForceRefresh} to ensure the next {@link #refresh()} cycle
     * bypasses the Redis cache — even if a non-forced refresh is already in progress.
     * This prevents the race where a concurrent {@code refreshAsync()} reads a stale
     * cache and the forced refresh is silently dropped.
     */
    public void forceRefresh() {
        pendingForceRefresh.set(true);
        redisTemplate.delete(CACHE_KEY)
                .doOnSuccess(deleted -> log.debug("Route cache invalidated"))
                .subscribe(v -> refreshAsync());
    }

    public int getLoadedRouteCount() {
        return currentRoutes.get().size();
    }

    /**
     * Periodic background refresh — safety net against lost Kafka events or
     * transient RabbitMQ failures. Runs every 2 minutes.
     *
     * <p>Forces a cache-bypassing refresh so that even if the Redis-cached snapshot
     * is stale and GATEWAY_RELOAD events were lost, the gateway will self-heal
     * within at most 2 minutes.
     */
    @Scheduled(fixedDelayString = "${routify.gateway.background-refresh-ms:120000}")
    public void periodicRefresh() {
        log.debug("Periodic background refresh triggered");
        forceRefresh();
    }

    private void cacheToRedis(List<RouteSnapshotDto> snapshots) {
        try {
            String json = objectMapper.writeValueAsString(snapshots);
            redisTemplate.opsForValue().set(CACHE_KEY, json, CACHE_TTL)
                    .subscribe(ok -> log.debug("Cached {} routes to Redis", snapshots.size()));
        } catch (Exception e) {
            log.warn("Failed to cache routes to Redis: {}", e.getMessage());
        }
    }
}

