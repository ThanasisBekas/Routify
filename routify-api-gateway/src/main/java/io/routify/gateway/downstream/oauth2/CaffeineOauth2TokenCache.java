package io.routify.gateway.downstream.oauth2;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Thread-safe, reactive OAuth2 token cache backed by Caffeine's {@code AsyncLoadingCache}.
 *
 * <p>TTL is derived from each token's {@code expires_in}/{@code expires} field
 * (with a 2-second skew); falls back to 30 s when no expiry is present.
 * Provides single-flight stampede protection.
 */
@Slf4j
public class CaffeineOauth2TokenCache {

    private static final long SKEW_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final long NO_EXPIRY_NANOS = TimeUnit.SECONDS.toNanos(30);

    private record CachedEntry(String accessToken, long lifetimeNanos) {}

    private final AsyncLoadingCache<String, CachedEntry> cache;
    private final Function<String, Mono<TokenResponse>> tokenFetcher;

    public CaffeineOauth2TokenCache(Function<String, Mono<TokenResponse>> tokenFetcher) {
        this.tokenFetcher = tokenFetcher;
        this.cache = buildCache();
    }

    /**
     * Returns the cached access token for {@code cacheKey}, fetching a new one if absent or expired.
     *
     * @param cacheKey unique key (typically {@code "password:<providerName>"})
     * @return a {@link Mono} emitting the access token string
     */
    public Mono<String> getToken(String cacheKey) {
        return Mono.fromFuture(cache.get(cacheKey))
                .map(CachedEntry::accessToken)
                .doOnNext(t -> log.debug("Token resolved for '{}' (stats: {})",
                        cacheKey, cache.synchronous().stats()));
    }

    /** Evicts a cached entry, forcing a fresh fetch on the next call. */
    public void invalidate(String cacheKey) {
        cache.synchronous().invalidate(cacheKey);
        log.debug("Token cache invalidated for '{}'", cacheKey);
    }

    /** Evicts all cached token entries. */
    public void invalidateAll() {
        int size = (int) cache.synchronous().estimatedSize();
        cache.synchronous().invalidateAll();
        log.info("Token cache fully invalidated ({} estimated entries)", size);
    }

    private AsyncLoadingCache<String, CachedEntry> buildCache() {
        return Caffeine.newBuilder()
                .expireAfter(new Expiry<String, CachedEntry>() {
                    @Override
                    public long expireAfterCreate(String key, CachedEntry value, long currentTime) {
                        return value.lifetimeNanos();
                    }
                    @Override
                    public long expireAfterUpdate(String key, CachedEntry value,
                                                  long currentTime, long currentDuration) {
                        return value.lifetimeNanos();
                    }
                    @Override
                    public long expireAfterRead(String key, CachedEntry value,
                                               long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .recordStats()
                .buildAsync((key, executor) -> {
                    log.debug("Cache miss for '{}' — fetching new token", key);
                    return tokenFetcher.apply(key)
                            .map(response -> {
                                long lifetimeNanos = resolveLifetimeNanos(response);
                                log.debug("Fetched token for '{}', lifetime={}s", key,
                                        TimeUnit.NANOSECONDS.toSeconds(lifetimeNanos));
                                return new CachedEntry(response.accessToken(), lifetimeNanos);
                            })
                            .toFuture();
                });
    }

    private static long resolveLifetimeNanos(TokenResponse response) {
        if (response.expiresInSeconds() != null && response.expiresInSeconds() > 0) {
            long nanos = TimeUnit.SECONDS.toNanos(response.expiresInSeconds());
            return Math.max(0, nanos - SKEW_NANOS);
        }
        if (response.expiresAtEpochMillis() != null && response.expiresAtEpochMillis() > 0) {
            long remainingMillis = response.expiresAtEpochMillis() - System.currentTimeMillis();
            long nanos = TimeUnit.MILLISECONDS.toNanos(remainingMillis);
            return Math.max(0, nanos - SKEW_NANOS);
        }
        return NO_EXPIRY_NANOS;
    }

    /**
     * DTO for token fetch results.
     *
     * @param accessToken          the OAuth2 access token
     * @param expiresInSeconds     seconds until expiry (from {@code expires_in}), nullable
     * @param expiresAtEpochMillis absolute expiry epoch millis (from {@code expires}), nullable
     */
    public record TokenResponse(String accessToken, Long expiresInSeconds, Long expiresAtEpochMillis) {}
}

