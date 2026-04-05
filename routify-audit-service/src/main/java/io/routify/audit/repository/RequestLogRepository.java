package io.routify.audit.repository;

import io.routify.audit.domain.RequestLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface RequestLogRepository extends JpaRepository<RequestLog, UUID> {

    Page<RequestLog> findByTenantIdOrderByRequestedAtDesc(UUID tenantId, Pageable pageable);

    Page<RequestLog> findByTenantIdAndRouteIdOrderByRequestedAtDesc(
            UUID tenantId, UUID routeId, Pageable pageable);

    @Query("""
            SELECT r FROM RequestLog r
            WHERE r.tenantId = :tenantId
            AND r.requestedAt BETWEEN :from AND :to
            ORDER BY r.requestedAt DESC
            """)
    Page<RequestLog> findByTenantAndTimeRange(
            @Param("tenantId") UUID tenantId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);

    @Query("""
            SELECT COUNT(r), AVG(r.durationMs), MAX(r.durationMs),
                   SUM(CASE WHEN r.responseStatus >= 400 THEN 1 ELSE 0 END)
            FROM RequestLog r
            WHERE r.tenantId = :tenantId
            AND r.routeId = :routeId
            AND r.requestedAt >= :since
            """)
    Object[] getRouteStats(
            @Param("tenantId") UUID tenantId,
            @Param("routeId") UUID routeId,
            @Param("since") Instant since);

    long countByTenantIdAndRequestedAtBetween(UUID tenantId, Instant from, Instant to);

    // ─── Failed request queries ───────────────────────────────────────────────

    /** All failed requests for a tenant with PENDING or FAILED replay status. */
    @Query("""
            SELECT r FROM RequestLog r
            WHERE r.tenantId = :tenantId
            AND r.failed = TRUE
            AND r.replayStatus IN ('PENDING', 'FAILED')
            ORDER BY r.requestedAt DESC
            """)
    Page<RequestLog> findPendingReplayByTenantId(
            @Param("tenantId") UUID tenantId,
            Pageable pageable);

    /** All failed requests for a specific route with PENDING or FAILED replay status. */
    @Query("""
            SELECT r FROM RequestLog r
            WHERE r.tenantId = :tenantId
            AND r.routeId = :routeId
            AND r.failed = TRUE
            AND r.replayStatus IN ('PENDING', 'FAILED')
            ORDER BY r.requestedAt DESC
            """)
    Page<RequestLog> findPendingReplayByTenantIdAndRouteId(
            @Param("tenantId") UUID tenantId,
            @Param("routeId") UUID routeId,
            Pageable pageable);

    /** All failed requests regardless of replay status. */
    @Query("""
            SELECT r FROM RequestLog r
            WHERE r.tenantId = :tenantId
            AND r.failed = TRUE
            ORDER BY r.requestedAt DESC
            """)
    Page<RequestLog> findFailedByTenantId(
            @Param("tenantId") UUID tenantId,
            Pageable pageable);

    /** All failed requests for a specific route, regardless of replay status. */
    @Query("""
            SELECT r FROM RequestLog r
            WHERE r.tenantId = :tenantId
            AND r.routeId = :routeId
            AND r.failed = TRUE
            ORDER BY r.requestedAt DESC
            """)
    Page<RequestLog> findFailedByTenantIdAndRouteId(
            @Param("tenantId") UUID tenantId,
            @Param("routeId") UUID routeId,
            Pageable pageable);

    /** Batch-load up to {@code limit} PENDING requests older than a given threshold for background replay. */
    @Query(value = """
            SELECT * FROM routify_audit.request_log
            WHERE failed = TRUE
            AND replay_status IN ('PENDING', 'FAILED')
            AND replay_count < :maxAttempts
            AND requested_at < :before
            ORDER BY requested_at ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<RequestLog> findReplayableRequests(
            @Param("before") Instant before,
            @Param("maxAttempts") int maxAttempts,
            @Param("limit") int limit);

    /** Counts failed requests by status for a tenant. */
    @Query("""
            SELECT r.replayStatus, COUNT(r)
            FROM RequestLog r
            WHERE r.tenantId = :tenantId
            AND r.failed = TRUE
            GROUP BY r.replayStatus
            """)
    List<Object[]> countFailedByReplayStatus(@Param("tenantId") UUID tenantId);

    /** Efficient bulk delete for retention — leverages partition pruning on requested_at. */
    @Modifying
    @Query(value = "DELETE FROM routify_audit.request_log WHERE requested_at < :cutoff", nativeQuery = true)
    int deleteByRequestedAtBefore(@Param("cutoff") Instant cutoff);
}



