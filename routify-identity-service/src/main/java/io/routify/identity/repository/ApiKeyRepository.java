package io.routify.identity.repository;

import io.routify.identity.domain.ApiKey;
import io.routify.identity.domain.ApiKeyStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    Page<ApiKey> findByTenantId(UUID tenantId, Pageable pageable);

    Page<ApiKey> findByTenantIdAndStatus(UUID tenantId, ApiKeyStatus status, Pageable pageable);

    Optional<ApiKey> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<ApiKey> findByKeyHash(String keyHash);

    /**
     * Bulk-update ACTIVE keys whose expiry has passed to EXPIRED status.
     * Returns the number of rows updated.
     */
    @Modifying
    @Query("UPDATE ApiKey k SET k.status = 'EXPIRED' WHERE k.status = 'ACTIVE' AND k.expiresAt IS NOT NULL AND k.expiresAt < :now")
    int expireActiveKeysBefore(@Param("now") Instant now);

    /** Find ACTIVE keys that have expired — used for Redis cleanup after bulk update. */
    List<ApiKey> findByStatusAndExpiresAtBefore(ApiKeyStatus status, Instant now);
}

