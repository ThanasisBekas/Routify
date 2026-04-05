package io.routify.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.routify.ai.dto.AiModificationRequest;
import io.routify.ai.dto.AiModificationResponse;
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

/**
 * Redis-based mutation result caching for the AI Modification Filter.
 *
 * <h3>Cache key strategy</h3>
 * Key = {@code routify:ai:mutation:{routeId}:{sha256(method+path+body+modificationPrompt)}}
 *
 * <h3>Why {@code cacheEnabled=false} is the default</h3>
 * Unlike the AI filter (where the same request always produces the same verdict),
 * mutations are often body-content-dependent. Caching a mutation of request A and
 * applying it to structurally-similar but distinct request B may produce incorrect results.
 *
 * Operators should only enable caching for mutations that are truly request-fingerprint
 * invariant (e.g. adding a fixed header, or scrubbing a known field by name).
 *
 * <h3>Redis key format</h3>
 * <pre>routify:ai:mutation:{routeId}:{sha256Hex}</pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MutationCacheService {

    static final String KEY_PREFIX = "routify:ai:mutation:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper        objectMapper;

    /**
     * Looks up a cached mutation result.
     *
     * @param request the modification request (used to compute the fingerprint)
     * @return an Optional containing the cached response, or empty on cache miss / error
     */
    public Optional<AiModificationResponse> get(AiModificationRequest request) {
        if (!request.modifierConfig().cacheEnabled()) return Optional.empty();

        String key = buildKey(request);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                log.debug("AI mutation cache MISS: key={}", key);
                return Optional.empty();
            }
            log.debug("AI mutation cache HIT: key={}", key);
            return Optional.of(objectMapper.readValue(json, AiModificationResponse.class));
        } catch (Exception e) {
            log.warn("AI mutation cache read error (key={}) — proceeding without cache: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Stores a mutation result in Redis with the configured TTL.
     *
     * @param request  the modification request (used to compute the fingerprint)
     * @param response the mutation result to cache
     */
    public void put(AiModificationRequest request, AiModificationResponse response) {
        if (!request.modifierConfig().cacheEnabled()) return;

        String key = buildKey(request);
        try {
            String json = objectMapper.writeValueAsString(response);
            int ttl = request.modifierConfig().cacheTtlSeconds();
            redisTemplate.opsForValue().set(key, json, Duration.ofSeconds(ttl));
            log.debug("AI mutation cached: key={} ttl={}s applied={}", key, ttl, response.mutationApplied());
        } catch (JsonProcessingException e) {
            log.warn("AI mutation cache write error (key={}) — result not cached: {}", key, e.getMessage());
        }
    }

    // ─── Key construction ─────────────────────────────────────────────────────

    String buildKey(AiModificationRequest request) {
        AiModificationRequest.RequestContext ctx = request.requestContext();
        AiModificationRequest.AiModifierConfig cfg = request.modifierConfig();

        String fingerprint = sha256(
                nullSafe(ctx.method())
                + "|" + nullSafe(ctx.path())
                + "|" + nullSafe(ctx.queryString())
                + "|" + nullSafe(ctx.bodyBase64())
                + "|" + nullSafe(cfg.modificationPrompt())
                + "|" + nullSafe(cfg.targetFields())
        );
        return KEY_PREFIX + request.routeId() + ":" + fingerprint;
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String nullSafe(String s) {
        return s != null ? s : "";
    }
}

