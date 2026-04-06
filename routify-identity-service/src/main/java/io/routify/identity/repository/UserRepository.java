package io.routify.identity.repository;

import io.routify.identity.domain.AppUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findByUsernameAndTenantId(String username, UUID tenantId);
    Optional<AppUser> findByEmailAndTenantId(String email, UUID tenantId);
    Optional<AppUser> findByIdAndTenantId(UUID id, UUID tenantId);
    Page<AppUser> findAllByTenantId(UUID tenantId, Pageable pageable);
    /** Cross-tenant query — only used by SUPER_ADMIN to list all users across all workspaces. */
    Page<AppUser> findAllByStatusNot(AppUser.Status status, Pageable pageable);
    boolean existsByUsernameAndTenantId(String username, UUID tenantId);
    boolean existsByEmailAndTenantId(String email, UUID tenantId);
    long countByTenantId(UUID tenantId);
}

