package gr.routify.cert.repository;

import gr.routify.cert.domain.StoredCertificate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link StoredCertificate}.
 */
@Repository
public interface StoredCertificateRepository extends JpaRepository<StoredCertificate, UUID> {

    Optional<StoredCertificate> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<StoredCertificate> findByLogicalIdAndTenantId(String logicalId, UUID tenantId);

    boolean existsByLogicalIdAndTenantId(String logicalId, UUID tenantId);

    boolean existsByAliasAndTenantId(String alias, UUID tenantId);

    boolean existsByAliasAndTenantIdAndIdNot(String alias, UUID tenantId, UUID excludeId);

    Page<StoredCertificate> findByTenantId(UUID tenantId, Pageable pageable);

    Page<StoredCertificate> findByTenantIdAndStatus(UUID tenantId, StoredCertificate.CertStatus status, Pageable pageable);

    List<StoredCertificate> findByTenantIdAndStatusOrderByAlias(
            UUID tenantId, StoredCertificate.CertStatus status);

    /** All active certs ordered by alias — used for vault listing snapshots */
    List<StoredCertificate> findByStatusOrderByAlias(StoredCertificate.CertStatus status);

    /** Active certs expiring before a given instant (for expiry alerting) */
    @Query("SELECT c FROM StoredCertificate c WHERE c.status = 'ACTIVE' AND c.expiresAt < :threshold")
    List<StoredCertificate> findExpiringBefore(@Param("threshold") Instant threshold);

    /** Count per status for a tenant */
    @Query("SELECT c.status, COUNT(c) FROM StoredCertificate c WHERE c.tenantId = :tenantId GROUP BY c.status")
    List<Object[]> countByStatusForTenant(@Param("tenantId") UUID tenantId);

    /** Platform-wide count per status */
    @Query("SELECT c.status, COUNT(c) FROM StoredCertificate c GROUP BY c.status")
    List<Object[]> countByStatus();

    /** All active certs for a tenant that have a gateway TLS mapping */
    List<StoredCertificate> findByTenantIdAndStatusAndGatewayTlsLogicalIdNotNull(
            UUID tenantId, StoredCertificate.CertStatus status);

    /** All active certs across ALL tenants that have a gateway TLS mapping (used by the gateway snapshot) */
    List<StoredCertificate> findByStatusAndGatewayTlsLogicalIdNotNull(StoredCertificate.CertStatus status);

    // ─── Group-based finders ──────────────────────────────────────────────────

    /** All certs belonging to a specific group */
    List<StoredCertificate> findByGroupId(UUID groupId);

    /** Active certs belonging to a specific group, ordered by memberAlias */
    List<StoredCertificate> findByGroupIdAndStatusOrderByMemberAlias(
            UUID groupId, StoredCertificate.CertStatus status);

    /** Find a cert by member alias within a group */
    @Query("SELECT c FROM StoredCertificate c WHERE c.group.id = :groupId AND c.memberAlias = :memberAlias")
    java.util.Optional<StoredCertificate> findByGroupIdAndMemberAlias(
            @Param("groupId") UUID groupId, @Param("memberAlias") String memberAlias);

    /** All active certs that belong to a group (group binding supersedes individual gateway_tls_logical_id) */
    @Query("SELECT c FROM StoredCertificate c WHERE c.status = 'ACTIVE' AND c.group IS NOT NULL")
    List<StoredCertificate> findActiveGroupedCerts();

    /** Active certs belonging to a group, scoped to a tenant */
    @Query("SELECT c FROM StoredCertificate c WHERE c.status = 'ACTIVE' AND c.group IS NOT NULL AND c.tenantId = :tenantId")
    List<StoredCertificate> findActiveGroupedCertsByTenant(@Param("tenantId") UUID tenantId);
}

