package gr.routify.route.repository;

import gr.routify.common.domain.FilterType;
import gr.routify.route.domain.FilterDefinition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FilterDefinitionRepository extends JpaRepository<FilterDefinition, UUID> {

    Page<FilterDefinition> findAllByTenantId(UUID tenantId, Pageable pageable);

    Page<FilterDefinition> findAllByTenantIdAndFilterType(
            UUID tenantId, FilterType filterType, Pageable pageable);

    Optional<FilterDefinition> findByIdAndTenantId(UUID id, UUID tenantId);

    boolean existsByNameAndTenantId(String name, UUID tenantId);

    List<FilterDefinition> findAllByTenantIdAndEnabledTrue(UUID tenantId);

    @Query("""
            SELECT f FROM FilterDefinition f
            WHERE f.tenantId = :tenantId
            AND f.enabled = true
            AND f.filterType = :filterType
            """)
    List<FilterDefinition> findEnabledByTenantAndType(
            @Param("tenantId") UUID tenantId,
            @Param("filterType") FilterType filterType);

    long countByTenantId(UUID tenantId);

    /**
     * Atomically increments usage_count via a single UPDATE — fixes the in-memory
     * increment race condition identified in audit finding M2.
     * Must be called within an active {@code @Transactional} context.
     */
    @Modifying
    @Query(value = "UPDATE routify.filter_definition SET usage_count = usage_count + 1, updated_at = now() WHERE id = :filterId", nativeQuery = true)
    void incrementUsageAtomic(@Param("filterId") UUID filterId);

    /**
     * Atomically decrements usage_count (floored at 0) via a single UPDATE.
     * Must be called within an active {@code @Transactional} context.
     */
    @Modifying
    @Query(value = "UPDATE routify.filter_definition SET usage_count = GREATEST(0, usage_count - 1), updated_at = now() WHERE id = :filterId", nativeQuery = true)
    void decrementUsageAtomic(@Param("filterId") UUID filterId);
}

