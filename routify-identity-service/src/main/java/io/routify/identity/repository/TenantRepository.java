package io.routify.identity.repository;

import io.routify.identity.domain.Tenant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenantRepository extends JpaRepository<Tenant, UUID> {
    Optional<Tenant> findBySlug(String slug);
    boolean existsByName(String name);
    boolean existsBySlug(String slug);
    Page<Tenant> findAllByStatus(Tenant.Status status, Pageable pageable);
    List<Tenant> findAllByStatus(Tenant.Status status);
}

