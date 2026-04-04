package gr.routify.audit.repository;

import gr.routify.audit.domain.AiFilterDecision;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository for AI filter decision audit records.
 *
 * <p>All queries are read-only (no updates — records are immutable).
 */
@Repository
public interface AiFilterDecisionRepository extends JpaRepository<AiFilterDecision, UUID> {

    // ─── Paginated queries for decision log ───────────────────────────────────

    Page<AiFilterDecision> findByTenantIdOrderByEvaluatedAtDesc(UUID tenantId, Pageable pageable);

    Page<AiFilterDecision> findByTenantIdAndRouteIdOrderByEvaluatedAtDesc(
            UUID tenantId, UUID routeId, Pageable pageable);

    Page<AiFilterDecision> findByTenantIdAndRouteIdAndActionOrderByEvaluatedAtDesc(
            UUID tenantId, UUID routeId, String action, Pageable pageable);

    Page<AiFilterDecision> findByTenantIdAndActionOrderByEvaluatedAtDesc(
            UUID tenantId, String action, Pageable pageable);

    @Query("""
           SELECT d FROM AiFilterDecision d
           WHERE d.tenantId = :tenantId
             AND d.evaluatedAt >= :from AND d.evaluatedAt < :to
           ORDER BY d.evaluatedAt DESC
           """)
    Page<AiFilterDecision> findByTenantAndTimeRange(
            @Param("tenantId") UUID tenantId,
            @Param("from")     Instant from,
            @Param("to")       Instant to,
            Pageable pageable);

    @Query("""
           SELECT d FROM AiFilterDecision d
           WHERE d.tenantId = :tenantId
             AND d.routeId = :routeId
             AND d.evaluatedAt >= :from AND d.evaluatedAt < :to
           ORDER BY d.evaluatedAt DESC
           """)
    Page<AiFilterDecision> findByTenantAndRouteAndTimeRange(
            @Param("tenantId") UUID tenantId,
            @Param("routeId")  UUID routeId,
            @Param("from")     Instant from,
            @Param("to")       Instant to,
            Pageable pageable);

    // ─── Aggregated stats queries ─────────────────────────────────────────────

    /**
     * Returns aggregated stats for a tenant in a time window.
     * Result columns: [total, allow, block, flag, fallback, cacheHits, avgLatency, p95Latency, p99Latency]
     *
     * <p>Note: PostgreSQL's {@code percentile_cont} is used for p95/p99.
     * "fallback" decisions are identified by {@code confidence = 0.0} — the convention
     * established in {@link gr.routify.ai.dto.RouteEvaluationResponse#fallback}.
     */
    @Query(value = """
           SELECT
               COUNT(*)                                                       AS total,
               COUNT(*) FILTER (WHERE action = 'ALLOW')                      AS allow_count,
               COUNT(*) FILTER (WHERE action = 'BLOCK')                      AS block_count,
               COUNT(*) FILTER (WHERE action = 'FLAG')                       AS flag_count,
               COUNT(*) FILTER (WHERE confidence = 0.0)                     AS fallback_count,
               COUNT(*) FILTER (WHERE cached = TRUE)                         AS cache_hit_count,
               COALESCE(AVG(latency_ms), 0)                                  AS avg_latency,
               COALESCE(PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY latency_ms), 0) AS p95_latency,
               COALESCE(PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY latency_ms), 0) AS p99_latency
           FROM routify_audit.ai_filter_decision
           WHERE tenant_id = :tenantId
             AND evaluated_at >= :from
             AND evaluated_at < :to
           """, nativeQuery = true)
    Object[] getStatsByTenantAndTimeRange(
            @Param("tenantId") UUID    tenantId,
            @Param("from")     Instant from,
            @Param("to")       Instant to);

    /**
     * Same as above but scoped to a single route.
     */
    @Query(value = """
           SELECT
               COUNT(*)                                                       AS total,
               COUNT(*) FILTER (WHERE action = 'ALLOW')                      AS allow_count,
               COUNT(*) FILTER (WHERE action = 'BLOCK')                      AS block_count,
               COUNT(*) FILTER (WHERE action = 'FLAG')                       AS flag_count,
               COUNT(*) FILTER (WHERE confidence = 0.0)                     AS fallback_count,
               COUNT(*) FILTER (WHERE cached = TRUE)                         AS cache_hit_count,
               COALESCE(AVG(latency_ms), 0)                                  AS avg_latency,
               COALESCE(PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY latency_ms), 0) AS p95_latency,
               COALESCE(PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY latency_ms), 0) AS p99_latency
           FROM routify_audit.ai_filter_decision
           WHERE tenant_id = :tenantId
             AND route_id  = :routeId
             AND evaluated_at >= :from
             AND evaluated_at < :to
           """, nativeQuery = true)
    Object[] getStatsByTenantAndRouteAndTimeRange(
            @Param("tenantId") UUID    tenantId,
            @Param("routeId")  UUID    routeId,
            @Param("from")     Instant from,
            @Param("to")       Instant to);

    // ─── Retention cleanup ────────────────────────────────────────────────────

    /** Bulk-delete records older than the given cutoff (called by retention scheduler). */
    @Query("DELETE FROM AiFilterDecision d WHERE d.evaluatedAt < :cutoff")
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    int deleteByEvaluatedAtBefore(@Param("cutoff") Instant cutoff);
}

