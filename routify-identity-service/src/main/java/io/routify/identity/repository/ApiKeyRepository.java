package io.routify.identity.repository;

import io.routify.identity.domain.ApiKey;
import io.routify.identity.domain.ApiKeyStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    Page<ApiKey> findByTenantId(UUID tenantId, Pageable pageable);

    Page<ApiKey> findByTenantIdAndStatus(UUID tenantId, ApiKeyStatus status, Pageable pageable);

    Optional<ApiKey> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<ApiKey> findByKeyHash(String keyHash);
}

