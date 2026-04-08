package io.routify.identity.repository;

import io.routify.identity.domain.RoleDefinition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link RoleDefinition} entities.
 *
 * <p>Built-in roles have {@code tenantId=null} and are visible to all tenants.
 * Custom roles are tenant-scoped.
 */
@Repository
public interface RoleDefinitionRepository extends JpaRepository<RoleDefinition, UUID> {

    /**
     * Returns built-in roles (tenantId IS NULL AND builtIn=true) plus
     * custom roles for the given tenant.
     */
    @Query("SELECT r FROM RoleDefinition r WHERE r.builtIn = true OR r.tenantId = :tenantId ORDER BY r.builtIn DESC, r.name ASC")
    List<RoleDefinition> findAllForTenant(UUID tenantId);

    /**
     * Paginated version of {@link #findAllForTenant(UUID)}.
     */
    @Query("SELECT r FROM RoleDefinition r WHERE r.builtIn = true OR r.tenantId = :tenantId")
    Page<RoleDefinition> findAllForTenant(UUID tenantId, Pageable pageable);

    /**
     * Find a role by name within a tenant scope (includes built-in roles with NULL tenantId).
     */
    @Query("SELECT r FROM RoleDefinition r WHERE r.name = :name AND (r.tenantId = :tenantId OR (r.tenantId IS NULL AND r.builtIn = true))")
    Optional<RoleDefinition> findByNameForTenant(String name, UUID tenantId);

    /**
     * Find a built-in role by name.
     */
    Optional<RoleDefinition> findByNameAndBuiltInTrue(String name);
}

