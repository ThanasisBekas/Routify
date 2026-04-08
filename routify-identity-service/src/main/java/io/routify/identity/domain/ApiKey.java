package io.routify.identity.domain;

import io.routify.common.domain.UserRole;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Durable API key entity — the source of truth for API key lifecycle.
 *
 * <p>Raw key material is never stored; only the SHA-256 hash ({@code keyHash})
 * is persisted. The {@code keyPrefix} (first 8 chars, e.g. {@code "rtfy_a1b2"})
 * is kept for human identification in list views.
 *
 * <p>On create/rotate the raw key is projected to Redis
 * ({@code RedisKeys.APIKEY_PREFIX + rawKey}) for zero-latency gateway reads.
 * On revoke the Redis key is deleted immediately.
 */
@Entity
@Table(name = "api_key", schema = "routify_identity")
@Getter @Setter
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String name;

    /** SHA-256 hex digest of the raw key. */
    @Column(name = "key_hash", nullable = false, unique = true)
    private String keyHash;

    /** First 8 characters of the raw key for display (e.g. "rtfy_a1b2"). */
    @Column(name = "key_prefix", nullable = false, length = 12)
    private String keyPrefix;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private UserRole role;

    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ApiKeyStatus status;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "created_by")
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected ApiKey() {}

    public ApiKey(UUID tenantId, UUID userId, String name, String keyHash, String keyPrefix,
                  UserRole role, String email, Instant expiresAt, UUID createdBy) {
        this.tenantId  = tenantId;
        this.userId    = userId;
        this.name      = name;
        this.keyHash   = keyHash;
        this.keyPrefix = keyPrefix;
        this.role      = role;
        this.email     = email;
        this.status    = ApiKeyStatus.ACTIVE;
        this.expiresAt = expiresAt;
        this.createdBy = createdBy;
    }

    public void revoke() {
        this.status    = ApiKeyStatus.REVOKED;
        this.revokedAt = Instant.now();
    }

    /** Marks this key as expired. Called by the expiry scheduler. */
    public void expire() {
        this.status = ApiKeyStatus.EXPIRED;
    }

    /** Returns true if this key has a set expiry date that is in the past. */
    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(Instant.now());
    }

    /**
     * Returns the effective status — if the key is ACTIVE but past its expiry date,
     * the effective status is EXPIRED (the scheduler will catch up eventually).
     */
    public ApiKeyStatus getEffectiveStatus() {
        if (status == ApiKeyStatus.ACTIVE && isExpired()) {
            return ApiKeyStatus.EXPIRED;
        }
        return status;
    }
}

