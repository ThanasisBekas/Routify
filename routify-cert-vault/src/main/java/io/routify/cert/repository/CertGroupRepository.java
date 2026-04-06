package io.routify.cert.repository;

import io.routify.cert.domain.CertGroup;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link CertGroup}.
 */
@Repository
public interface CertGroupRepository extends JpaRepository<CertGroup, UUID> {

    Optional<CertGroup> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<CertGroup> findByLogicalIdAndTenantId(String logicalId, UUID tenantId);

    boolean existsByLogicalIdAndTenantId(String logicalId, UUID tenantId);

    boolean existsByAliasAndTenantId(String alias, UUID tenantId);

    boolean existsByLogicalIdAndTenantIdAndIdNot(String logicalId, UUID tenantId, UUID excludeId);

    boolean existsByAliasAndTenantIdAndIdNot(String alias, UUID tenantId, UUID excludeId);

    Page<CertGroup> findByTenantId(UUID tenantId, Pageable pageable);

    Page<CertGroup> findByTenantIdAndStatus(UUID tenantId, CertGroup.GroupStatus status, Pageable pageable);
}

