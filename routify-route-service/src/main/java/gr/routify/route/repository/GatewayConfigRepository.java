package gr.routify.route.repository;

import gr.routify.route.domain.GatewayConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * JPA repository for durable gateway configuration.
 */
@Repository
public interface GatewayConfigRepository extends JpaRepository<GatewayConfig, UUID> {

    /**
     * Find platform-wide config by key (tenant_id IS NULL).
     * Used for global gateway config like CORS, security headers, etc.
     */
    @Query("SELECT g FROM GatewayConfig g WHERE g.configKey = :configKey AND g.tenantId IS NULL")
    Optional<GatewayConfig> findByConfigKeyAndNullTenant(String configKey);

    /**
     * Find tenant-specific config override by key.
     * Returns empty if no per-tenant override exists (caller falls back to global).
     */
    Optional<GatewayConfig> findByConfigKeyAndTenantId(String configKey, UUID tenantId);
}

