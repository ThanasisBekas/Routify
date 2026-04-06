package io.routify.ai.service;

import io.routify.ai.dto.RouteEvaluationRequest;
import io.routify.ai.dto.RouteEvaluationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Manages Redis-based verdict caching for the AI filter.
 *
 * <h3>Cache key strategy</h3>
 * Key = {@code routify:ai:verdict:{routeId}:{sha256(method+path+queryString+bodyExcerpt+policyDescription)}}
 *
 * <p>The SHA-256 fingerprint ensures that structurally identical requests (same
 * method, path, body, and policy) always hit the same cache entry, while any
 * mutation — including a policy change — produces a new key automatically.
 *
 * <h3>Why this is safe</h3>
 * The LLM is called with {@code temperature=0.0}, meaning identical inputs yield
 * identical outputs. Caching the verdict therefore does not introduce non-determinism
 * beyond what the LLM already provides. The configurable TTL (default 30s) limits the
 * window during which a stale verdict could be served after a policy change.
 *
 * <h3>Redis key format</h3>
 * <pre>routify:ai:verdict:{routeId}:{sha256Hex}</pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerdictCacheService {

    static final String KEY_PREFIX = "routify:ai:verdict:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper        objectMapper;

    /**
     * Looks up a cached verdict.
     *
     * @param request the evaluation request (used to compute the fingerprint)
     * @return an Optional containing the cached response, or empty on cache miss / error
     */
    public Optional<RouteEvaluationResponse> get(RouteEvaluationRequest request) {
        if (!request.filterConfig().cacheEnabled()) {
            return Optional.empty();
        }
        String key = buildKey(request);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                log.debug("AI verdict cache MISS: key={}", key);
                return Optional.empty();
            }
            log.debug("AI verdict cache HIT: key={}", key);
            return Optional.of(objectMapper.readValue(json, RouteEvaluationResponse.class));
        } catch (Exception e) {
            // Cache failures must never block the evaluation path
            log.warn("AI verdict cache read error (key={}) — proceeding without cache: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Stores a verdict in Redis with the configured TTL.
     *
     * @param request  the evaluation request (used to compute the fingerprint)
     * @param response the verdict to cache
     */
    public void put(RouteEvaluationRequest request, RouteEvaluationResponse response) {
        if (!request.filterConfig().cacheEnabled()) {
            return;
        }
        String key = buildKey(request);
        try {
            String json = objectMapper.writeValueAsString(response);
            int ttl = request.filterConfig().cacheTtlSeconds();
            redisTemplate.opsForValue().set(key, json, Duration.ofSeconds(ttl));
            log.debug("AI verdict cached: key={} ttl={}s action={}", key, ttl, response.action());
        } catch (JsonProcessingException e) {
            log.warn("AI verdict cache write error (key={}) — verdict not cached: {}", key, e.getMessage());
        }
    }

    // ─── Key construction ─────────────────────────────────────────────────────

    String buildKey(RouteEvaluationRequest request) {
        RouteEvaluationRequest.RequestContext ctx = request.requestContext();
        RouteEvaluationRequest.AiFilterConfig cfg = request.filterConfig();

        // Fingerprint = SHA-256 of the request-invariant fields + policy
        // Any change to method, path, body, or policy → different cache key
        String fingerprint = sha256(
                nullSafe(ctx.method())
                + "|" + nullSafe(ctx.path())
                + "|" + nullSafe(ctx.queryString())
                + "|" + nullSafe(ctx.bodyExcerpt())
                + "|" + nullSafe(cfg.policyDescription())
        );
        return KEY_PREFIX + request.routeId() + ":" + fingerprint;
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the JVM spec — this cannot happen
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String nullSafe(String s) {
        return s != null ? s : "";
    }
}

