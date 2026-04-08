package io.routify.audit.repository;

import io.routify.audit.domain.AiPromptVersion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for AI prompt version management.
 *
 * <p>Versions are created as DRAFT, activated (ACTIVE), and eventually archived (ARCHIVED).
 * Only one ACTIVE version per filter at any time.
 */
@Repository
public interface AiPromptVersionRepository extends JpaRepository<AiPromptVersion, UUID> {

    /** Paginated version list for a filter, newest first. */
    Page<AiPromptVersion> findByFilterIdAndTenantIdOrderByVersionDesc(
            UUID filterId, UUID tenantId, Pageable pageable);

    /** Get a single version by ID scoped to tenant. */
    Optional<AiPromptVersion> findByIdAndTenantId(UUID id, UUID tenantId);

    /** Find the currently active version for a filter. */
    Optional<AiPromptVersion> findByFilterIdAndTenantIdAndStatus(
            UUID filterId, UUID tenantId, String status);

    /** Find all versions with a given status for a filter (for bulk archival). */
    List<AiPromptVersion> findAllByFilterIdAndTenantIdAndStatus(
            UUID filterId, UUID tenantId, String status);

    /** Get the highest version number for a filter (for auto-incrementing). */
    @Query(value = "SELECT COALESCE(MAX(v.version), 0) FROM routify_audit.ai_prompt_version v " +
                   "WHERE v.filter_id = :filterId AND v.tenant_id = :tenantId",
           nativeQuery = true)
    int findMaxVersionByFilterIdAndTenantId(
            @Param("filterId") UUID filterId,
            @Param("tenantId") UUID tenantId);
}

