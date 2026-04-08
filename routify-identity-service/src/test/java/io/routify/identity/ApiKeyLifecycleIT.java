package io.routify.identity;

import io.routify.common.domain.UserRole;
import io.routify.common.security.RedisKeys;
import io.routify.identity.domain.ApiKey;
import io.routify.identity.domain.ApiKeyStatus;
import io.routify.identity.service.ApiKeyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the API key lifecycle: create → Redis projection → revoke.
 *
 * <p>Uses real PostgreSQL + Redis + Kafka + RabbitMQ containers via Testcontainers.
 * Validates that:
 * <ul>
 *   <li>Key creation persists a hashed key in Postgres and projects raw key to Redis</li>
 *   <li>Redis contains the correct tenant/user/role fields for gateway reads</li>
 *   <li>Revocation removes the key from Redis and marks it as REVOKED in Postgres</li>
 *   <li>Rotation creates a new key and revokes the old one atomically</li>
 * </ul>
 */
class ApiKeyLifecycleIT extends IdentityServiceIntegrationBase {

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private io.routify.identity.repository.ApiKeyRepository apiKeyRepository;

    /**
     * Retrieve the tenant ID from the seeded "platform" tenant (created by DataSeeder).
     */
    private UUID getPlatformTenantId() {
        return tenantRepository.findAll().stream()
                .filter(t -> "platform".equals(t.getSlug()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Platform tenant not seeded"))
                .getId();
    }

    /**
     * Retrieve the admin user ID (created by DataSeeder).
     */
    private UUID getAdminUserId() {
        return userRepository.findAll().stream()
                .filter(u -> ADMIN_USERNAME.equals(u.getUsername()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Admin user not seeded"))
                .getId();
    }

    @Test
    @DisplayName("Create API key → persists hashed key in Postgres and projects to Redis")
    void create_persistsKeyAndProjectsToRedis() {
        UUID tenantId = getPlatformTenantId();
        UUID userId   = getAdminUserId();

        ApiKeyService.CreateResult result = apiKeyService.create(
                tenantId, userId, "Test API Key", UserRole.OPERATOR,
                "test@routify.io", null, "test-actor");

        // 1. Raw key starts with "rtfy_" prefix
        assertThat(result.rawKey()).startsWith("rtfy_");
        assertThat(result.rawKey()).hasSize(40); // "rtfy_" + 35 base62 chars

        // 2. Persisted entity has correct fields
        ApiKey saved = result.apiKey();
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getTenantId()).isEqualTo(tenantId);
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getName()).isEqualTo("Test API Key");
        assertThat(saved.getRole()).isEqualTo(UserRole.OPERATOR);
        assertThat(saved.getStatus()).isEqualTo(ApiKeyStatus.ACTIVE);
        assertThat(saved.getKeyHash()).isNotBlank();
        assertThat(saved.getKeyPrefix()).isEqualTo(result.rawKey().substring(0, 12));

        // 3. Redis contains the projected key
        String redisKey = RedisKeys.APIKEY_PREFIX + result.rawKey();
        var entries = redisTemplate.opsForHash().entries(redisKey);
        assertThat(entries).containsEntry("tenantId", tenantId.toString());
        assertThat(entries).containsEntry("userId", userId.toString());
        assertThat(entries).containsEntry("role", "OPERATOR");
        assertThat(entries).containsEntry("email", "test@routify.io");
    }

    @Test
    @DisplayName("Revoke API key → removes from Redis and marks REVOKED in Postgres")
    void revoke_removesFromRedisAndMarksRevoked() {
        UUID tenantId = getPlatformTenantId();
        UUID userId   = getAdminUserId();

        ApiKeyService.CreateResult created = apiKeyService.create(
                tenantId, userId, "Revoke Test Key", UserRole.VIEWER,
                "viewer@routify.io", null, "test-actor");

        String redisKey = RedisKeys.APIKEY_PREFIX + created.rawKey();
        // Verify Redis projection exists before revoke
        assertThat(redisTemplate.hasKey(redisKey)).isTrue();

        // Revoke
        apiKeyService.revoke(created.apiKey().getId(), tenantId, "test-actor");

        // Verify Redis key is deleted
        assertThat(redisTemplate.hasKey(redisKey)).isFalse();

        // Verify Postgres status is REVOKED
        ApiKey revoked = apiKeyRepository.findById(created.apiKey().getId()).orElseThrow();
        assertThat(revoked.getStatus()).isEqualTo(ApiKeyStatus.REVOKED);
        assertThat(revoked.getRevokedAt()).isNotNull();
    }

    @Test
    @DisplayName("Rotate API key → revokes old key and creates new one with same metadata")
    void rotate_revokesOldAndCreatesNew() {
        UUID tenantId = getPlatformTenantId();
        UUID userId   = getAdminUserId();

        ApiKeyService.CreateResult original = apiKeyService.create(
                tenantId, userId, "Rotate Test Key", UserRole.TENANT_ADMIN,
                "admin@routify.io", null, "test-actor");

        String originalRedisKey = RedisKeys.APIKEY_PREFIX + original.rawKey();
        assertThat(redisTemplate.hasKey(originalRedisKey)).isTrue();

        // Rotate
        ApiKeyService.CreateResult rotated = apiKeyService.rotate(
                original.apiKey().getId(), tenantId, "test-actor");

        // Old key should be revoked
        assertThat(redisTemplate.hasKey(originalRedisKey)).isFalse();
        ApiKey oldKey = apiKeyRepository.findById(original.apiKey().getId()).orElseThrow();
        assertThat(oldKey.getStatus()).isEqualTo(ApiKeyStatus.REVOKED);

        // New key should be active with same metadata
        assertThat(rotated.rawKey()).startsWith("rtfy_");
        assertThat(rotated.rawKey()).isNotEqualTo(original.rawKey());
        assertThat(rotated.apiKey().getName()).isEqualTo("Rotate Test Key");
        assertThat(rotated.apiKey().getRole()).isEqualTo(UserRole.TENANT_ADMIN);
        assertThat(rotated.apiKey().getStatus()).isEqualTo(ApiKeyStatus.ACTIVE);

        // New key should be in Redis
        String newRedisKey = RedisKeys.APIKEY_PREFIX + rotated.rawKey();
        assertThat(redisTemplate.hasKey(newRedisKey)).isTrue();
    }

    @Test
    @DisplayName("Revoking an already revoked key throws Conflict")
    void revoke_alreadyRevoked_throwsConflict() {
        UUID tenantId = getPlatformTenantId();
        UUID userId   = getAdminUserId();

        ApiKeyService.CreateResult created = apiKeyService.create(
                tenantId, userId, "Double Revoke Key", UserRole.VIEWER,
                "viewer@routify.io", null, "test-actor");

        apiKeyService.revoke(created.apiKey().getId(), tenantId, "test-actor");

        assertThatThrownBy(() -> apiKeyService.revoke(created.apiKey().getId(), tenantId, "test-actor"))
                .isInstanceOf(io.routify.common.exception.RoutifyException.Conflict.class);
    }

    @Test
    @DisplayName("Rotating a revoked key throws Conflict")
    void rotate_revokedKey_throwsConflict() {
        UUID tenantId = getPlatformTenantId();
        UUID userId   = getAdminUserId();

        ApiKeyService.CreateResult created = apiKeyService.create(
                tenantId, userId, "Rotate Revoked Key", UserRole.VIEWER,
                "viewer@routify.io", null, "test-actor");

        apiKeyService.revoke(created.apiKey().getId(), tenantId, "test-actor");

        assertThatThrownBy(() -> apiKeyService.rotate(created.apiKey().getId(), tenantId, "test-actor"))
                .isInstanceOf(io.routify.common.exception.RoutifyException.Conflict.class);
    }

    @Test
    @DisplayName("API key with expiration is projected to Redis with TTL")
    void create_withExpiration_setsRedisTtl() {
        UUID tenantId = getPlatformTenantId();
        UUID userId   = getAdminUserId();

        Instant expiresAt = Instant.now().plus(1, ChronoUnit.HOURS);
        ApiKeyService.CreateResult result = apiKeyService.create(
                tenantId, userId, "Expiring Key", UserRole.VIEWER,
                "viewer@routify.io", expiresAt, "test-actor");

        String redisKey = RedisKeys.APIKEY_PREFIX + result.rawKey();
        Long ttl = redisTemplate.getExpire(redisKey);
        // TTL should be set (positive value, within reasonable range)
        assertThat(ttl).isNotNull().isGreaterThan(0);
    }
}

