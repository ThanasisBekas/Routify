package io.routify.identity.service;

import io.routify.common.domain.UserRole;
import io.routify.common.event.DomainEvent;
import io.routify.common.event.KafkaTopics;
import io.routify.common.exception.RoutifyException;
import io.routify.common.security.RedisKeys;
import io.routify.identity.domain.ApiKey;
import io.routify.identity.domain.ApiKeyStatus;
import io.routify.identity.outbox.IdentityOutboxEventStore;
import io.routify.identity.repository.ApiKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * API key lifecycle management — create, revoke, rotate.
 *
 * <p>Raw keys are generated as {@code rtfy_} + 35 base62 chars (40 chars total).
 * Only the SHA-256 hash is persisted in Postgres. The raw key is projected to
 * Redis for zero-latency gateway lookups via {@code RedisKeys.APIKEY_PREFIX}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private static final String KEY_PREFIX = "rtfy_";
    private static final int KEY_RANDOM_LENGTH = 35;
    private static final String BASE62 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiKeyRepository apiKeyRepository;
    private final StringRedisTemplate redisTemplate;
    private final IdentityOutboxEventStore outboxStore;

    // ─── Queries ──────────────────────────────────────────────────────────────

    public Page<ApiKey> findAll(UUID tenantId, Pageable pageable) {
        return apiKeyRepository.findByTenantId(tenantId, pageable);
    }

    public ApiKey findById(UUID id, UUID tenantId) {
        return apiKeyRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new RoutifyException.NotFound("ApiKey", id.toString()));
    }

    // ─── Commands ─────────────────────────────────────────────────────────────

    /**
     * Creates a new API key. Returns the result record containing the raw key
     * (shown to the user exactly once) and the persisted entity.
     */
    @Transactional
    public CreateResult create(UUID tenantId, UUID userId, String name, UserRole role,
                               String email, Instant expiresAt, String actor) {
        String rawKey   = generateRawKey();
        String keyHash  = sha256(rawKey);
        String prefix   = rawKey.substring(0, Math.min(12, rawKey.length()));

        ApiKey apiKey = new ApiKey(tenantId, userId, name, keyHash, prefix, role, email, expiresAt,
                userId);
        apiKey = apiKeyRepository.save(apiKey);

        // Project to Redis for gateway reads
        projectToRedis(rawKey, tenantId, userId, role, email, expiresAt);

        // Outbox event for audit
        outboxStore.store(
                new DomainEvent.ApiKeyCreated(
                        UUID.randomUUID(), tenantId, apiKey.getId(), name, prefix,
                        role.name(), Instant.now(), null, actor),
                KafkaTopics.AUDIT_EVENTS,
                tenantId);

        log.info("API key created: id={} prefix={} tenant={}", apiKey.getId(), prefix, tenantId);
        return new CreateResult(rawKey, apiKey);
    }

    @Transactional
    public void revoke(UUID apiKeyId, UUID tenantId, String actor) {
        ApiKey apiKey = apiKeyRepository.findByIdAndTenantId(apiKeyId, tenantId)
                .orElseThrow(() -> new RoutifyException.NotFound("ApiKey", apiKeyId.toString()));

        if (apiKey.getStatus() == ApiKeyStatus.REVOKED) {
            throw new RoutifyException.Conflict("API key is already revoked");
        }

        // We need the raw key's Redis key — but we only have the hash.
        // Redis keys are stored as APIKEY_PREFIX + rawKey, and we cannot reverse the hash.
        // Instead, we delete by scanning or storing the Redis key reference.
        // The gateway uses the raw key as the Redis lookup key.
        // Since we can't reverse SHA-256, we store the key hash in Redis too
        // and use a reverse-lookup pattern: hash -> redis key.
        // Simpler approach: store a reverse mapping hash -> rawKey in a separate key.
        // Actually, the simplest and most robust approach: delete by pattern or
        // store the Redis key name alongside the entity.
        // For now, we'll delete using the hash-to-key mapping stored during creation.
        deleteFromRedisByHash(apiKey.getKeyHash());

        apiKey.revoke();
        apiKeyRepository.save(apiKey);

        outboxStore.store(
                new DomainEvent.ApiKeyRevoked(
                        UUID.randomUUID(), tenantId, apiKey.getId(), apiKey.getName(),
                        apiKey.getKeyPrefix(), Instant.now(), null, actor),
                KafkaTopics.AUDIT_EVENTS,
                tenantId);

        log.info("API key revoked: id={} prefix={} tenant={}", apiKeyId, apiKey.getKeyPrefix(), tenantId);
    }

    /**
     * Rotates an API key: revokes the old key and creates a new one with the
     * same metadata. Returns the new raw key (shown once).
     */
    @Transactional
    public CreateResult rotate(UUID apiKeyId, UUID tenantId, String actor) {
        ApiKey oldKey = apiKeyRepository.findByIdAndTenantId(apiKeyId, tenantId)
                .orElseThrow(() -> new RoutifyException.NotFound("ApiKey", apiKeyId.toString()));

        if (oldKey.getStatus() == ApiKeyStatus.REVOKED) {
            throw new RoutifyException.Conflict("Cannot rotate a revoked API key");
        }

        // Revoke old key
        deleteFromRedisByHash(oldKey.getKeyHash());
        oldKey.revoke();
        apiKeyRepository.save(oldKey);

        // Create new key with same metadata
        CreateResult result = create(tenantId, oldKey.getUserId(), oldKey.getName(),
                oldKey.getRole(), oldKey.getEmail(), oldKey.getExpiresAt(), actor);

        log.info("API key rotated: oldId={} newId={} tenant={}",
                apiKeyId, result.apiKey().getId(), tenantId);
        return result;
    }

    // ─── Redis projection ─────────────────────────────────────────────────────

    private void projectToRedis(String rawKey, UUID tenantId, UUID userId,
                                UserRole role, String email, Instant expiresAt) {
        String redisKey = RedisKeys.APIKEY_PREFIX + rawKey;
        redisTemplate.opsForHash().putAll(redisKey, Map.of(
                "tenantId", tenantId.toString(),
                "userId",   userId.toString(),
                "role",     role.name(),
                "email",    email != null ? email : ""
        ));
        if (expiresAt != null) {
            redisTemplate.expireAt(redisKey, expiresAt);
        }

        // Store reverse mapping: hash -> redisKey (for revoke/delete without raw key)
        String hashKey = RedisKeys.APIKEY_PREFIX + "hash:" + sha256(rawKey);
        redisTemplate.opsForValue().set(hashKey, redisKey);
        if (expiresAt != null) {
            redisTemplate.expireAt(hashKey, expiresAt);
        }
    }

    private void deleteFromRedisByHash(String keyHash) {
        String hashKey = RedisKeys.APIKEY_PREFIX + "hash:" + keyHash;
        String redisKey = redisTemplate.opsForValue().get(hashKey);
        if (redisKey != null) {
            redisTemplate.delete(redisKey);
            redisTemplate.delete(hashKey);
        }
    }

    // ─── Key generation helpers ───────────────────────────────────────────────

    private static String generateRawKey() {
        StringBuilder sb = new StringBuilder(KEY_PREFIX);
        for (int i = 0; i < KEY_RANDOM_LENGTH; i++) {
            sb.append(BASE62.charAt(RANDOM.nextInt(BASE62.length())));
        }
        return sb.toString();
    }

    static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // ─── Result type ──────────────────────────────────────────────────────────

    /** Carries both the raw key (shown once) and the persisted entity. */
    public record CreateResult(String rawKey, ApiKey apiKey) {}
}

