package io.routify.route.repository;

import io.routify.common.domain.RouteStatus;
import io.routify.route.domain.Route;
import io.routify.route.dto.RouteStatusCount;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RouteRepository extends JpaRepository<Route, UUID> {

    Page<Route> findAllByTenantId(UUID tenantId, Pageable pageable);

    Page<Route> findAllByTenantIdAndStatus(UUID tenantId, RouteStatus status, Pageable pageable);

    Optional<Route> findByIdAndTenantId(UUID id, UUID tenantId);

    /**
     * Fetch a single route by id and tenant with filters eagerly loaded.
     * Use this whenever the caller needs to access {@code route.getFilters()}
     * outside the transaction (e.g. in a RabbitMQ handler mapper call).
     */
    @Query("""
            SELECT r FROM Route r
            LEFT JOIN FETCH r.filters rf
            LEFT JOIN FETCH rf.filterDefinition
            WHERE r.id = :id AND r.tenantId = :tenantId
            """)
    Optional<Route> findByIdAndTenantIdWithFilters(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    boolean existsByNameAndTenantId(String name, UUID tenantId);

    /**
     * Returns true if there is already an ACTIVE route under the same tenant
     * with the same path pattern and methods combination.
     * Used before activation to prevent routing ambiguity in the live gateway.
     */
    @Query("""
            SELECT COUNT(r) > 0 FROM Route r
            WHERE r.tenantId = :tenantId
              AND r.pathPattern = :pathPattern
              AND r.methods = :methods
              AND r.status = 'ACTIVE'
            """)
    boolean existsActiveByPathPatternAndMethodsAndTenantId(
            @Param("tenantId") UUID tenantId,
            @Param("pathPattern") String pathPattern,
            @Param("methods") String methods);

    /**
     * Same as above but excludes a specific route ID — used when an already-ACTIVE route
     * is updated so it is not blocked by its own current entry.
     */
    @Query("""
            SELECT COUNT(r) > 0 FROM Route r
            WHERE r.tenantId = :tenantId
              AND r.pathPattern = :pathPattern
              AND r.methods = :methods
              AND r.status = 'ACTIVE'
              AND r.id != :excludeId
            """)
    boolean existsActiveByPathPatternAndMethodsAndTenantIdExcluding(
            @Param("tenantId") UUID tenantId,
            @Param("pathPattern") String pathPattern,
            @Param("methods") String methods,
            @Param("excludeId") UUID excludeId);

    List<Route> findAllByStatus(RouteStatus status);

    /**
     * Fetch all active routes with their filters eagerly loaded
     * for gateway snapshot generation. Avoids N+1 queries.
     */
    @Query("""
            SELECT DISTINCT r FROM Route r
            LEFT JOIN FETCH r.filters rf
            LEFT JOIN FETCH rf.filterDefinition
            WHERE r.status = 'ACTIVE'
            ORDER BY r.createdAt ASC
            """)
    List<Route> findAllActiveWithFilters();

    /**
     * Fetch active routes for a specific tenant with filters.
     * Used for tenant-scoped gateway configuration.
     */
    @Query("""
            SELECT DISTINCT r FROM Route r
            LEFT JOIN FETCH r.filters rf
            LEFT JOIN FETCH rf.filterDefinition
            WHERE r.tenantId = :tenantId AND r.status = 'ACTIVE'
            ORDER BY r.createdAt ASC
            """)
    List<Route> findActiveByTenantWithFilters(@Param("tenantId") UUID tenantId);

    long countByTenantId(UUID tenantId);

    long countByTenantIdAndStatus(UUID tenantId, RouteStatus status);

    @Query("SELECT COUNT(r) FROM Route r WHERE r.tenantId = :tenantId AND r.status != 'ARCHIVED'")
    long countActiveByTenantId(@Param("tenantId") UUID tenantId);

    /** Returns typed [status, count] pairs for all statuses. If tenantId is null, counts across all tenants. */
    @Query("SELECT new io.routify.route.dto.RouteStatusCount(r.status, COUNT(r)) FROM Route r WHERE (:tenantId IS NULL OR r.tenantId = :tenantId) GROUP BY r.status")
    List<RouteStatusCount> countByStatusForTenant(@Param("tenantId") UUID tenantId);

    /**
     * Eagerly fetches the filters (and their definitions) for a specific set of route IDs.
     * Used in a two-step pagination pattern: paginate first, then hydrate filters in a
     * second query to avoid the HHH90003004 warning and LazyInitializationException.
     */
    @Query("""
            SELECT DISTINCT r FROM Route r
            LEFT JOIN FETCH r.filters rf
            LEFT JOIN FETCH rf.filterDefinition
            WHERE r.id IN :ids
            """)
    List<Route> findAllWithFiltersByIds(@Param("ids") List<UUID> ids);
}

