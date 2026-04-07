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

    // ─── Route Health Dashboard v2 ────────────────────────────────────────────

    /**
     * Per-route aggregated health stats: total, errors, avg/p50/p95/p99 latency.
     * Uses native SQL for percentile_cont (PostgreSQL).
     */
    @Query(value = """
            SELECT r.route_id,
                   r.route_name,
                   COUNT(*)                                                                AS total,
                   SUM(CASE WHEN r.response_status >= 400 THEN 1 ELSE 0 END)              AS errors,
                   AVG(r.duration_ms)                                                      AS avg_latency,
                   percentile_cont(0.50) WITHIN GROUP (ORDER BY r.duration_ms)             AS p50,
                   percentile_cont(0.95) WITHIN GROUP (ORDER BY r.duration_ms)             AS p95,
                   percentile_cont(0.99) WITHIN GROUP (ORDER BY r.duration_ms)             AS p99
            FROM routify_audit.request_log r
            WHERE r.tenant_id = :tenantId
              AND r.requested_at >= :since
              AND r.route_id IS NOT NULL
            GROUP BY r.route_id, r.route_name
            ORDER BY total DESC
            """, nativeQuery = true)
    List<Object[]> getRouteHealthStats(
            @Param("tenantId") UUID tenantId,
            @Param("since") Instant since);

    /** Status code distribution for a single route within a time window. */
    @Query(value = """
            SELECT r.response_status, COUNT(*)
            FROM routify_audit.request_log r
            WHERE r.tenant_id = :tenantId
              AND r.route_id = :routeId
              AND r.requested_at >= :since
              AND r.response_status IS NOT NULL
            GROUP BY r.response_status
            ORDER BY r.response_status
            """, nativeQuery = true)
    List<Object[]> getStatusCodeDistribution(
            @Param("tenantId") UUID tenantId,
            @Param("routeId") UUID routeId,
            @Param("since") Instant since);

    // ─── Tenant Usage Analytics ─────────────────────────────────────────────────

    /**
     * Aggregates request count and error count per tenant for a time period.
     * Returns rows of [tenant_id, request_count, error_count].
     */
    @Query(value = """
            SELECT r.tenant_id,
                   COUNT(*),
                   SUM(CASE WHEN r.response_status >= 400 THEN 1 ELSE 0 END)
            FROM routify_audit.request_log r
            WHERE r.requested_at >= :dayStart
              AND r.requested_at < :dayEnd
              AND r.tenant_id IS NOT NULL
            GROUP BY r.tenant_id
            """, nativeQuery = true)
    List<Object[]> countRequestsByTenantForPeriod(
            @Param("dayStart") Instant dayStart,
            @Param("dayEnd") Instant dayEnd);

    // ─── Time-Series Analytics (GraphQL Initiative 13) ─────────────────────────

    /**
     * Time-bucketed request metrics using {@code date_trunc} for configurable granularity.
     * Returns rows of [bucket, route_id, route_name, total, errors, avg_latency, p50, p95, p99].
     *
     * <p>The {@code granularity} parameter must be a valid PostgreSQL date_trunc field
     * (minute, hour, day, week). Caller validates before invoking.
     */
    @Query(value = """
            SELECT date_trunc(:granularity, r.requested_at)    AS bucket,
                   r.route_id,
                   r.route_name,
                   COUNT(*)                                     AS total,
                   SUM(CASE WHEN r.response_status >= 400 THEN 1 ELSE 0 END) AS errors,
                   AVG(r.duration_ms)                           AS avg_latency,
                   percentile_cont(0.50) WITHIN GROUP (ORDER BY r.duration_ms) AS p50,
                   percentile_cont(0.95) WITHIN GROUP (ORDER BY r.duration_ms) AS p95,
                   percentile_cont(0.99) WITHIN GROUP (ORDER BY r.duration_ms) AS p99
            FROM routify_audit.request_log r
            WHERE r.tenant_id = :tenantId
              AND r.requested_at >= CAST(:from AS TIMESTAMP WITH TIME ZONE)
              AND r.requested_at < CAST(:to AS TIMESTAMP WITH TIME ZONE)
              AND (:routeId IS NULL OR r.route_id = :routeId)
              AND r.route_id IS NOT NULL
            GROUP BY bucket, r.route_id, r.route_name
            ORDER BY bucket ASC, total DESC
            """, nativeQuery = true)
    List<Object[]> getTimeSeriesMetrics(
            @Param("tenantId") UUID tenantId,
            @Param("routeId") UUID routeId,
            @Param("from") String from,
            @Param("to") String to,
            @Param("granularity") String granularity);
}



