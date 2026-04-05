package io.routify.audit.repository;

import io.routify.audit.domain.AiModificationDecision;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Repository for AI Modification Filter audit records.
 *
 * <p>All queries are read-only (no updates — records are immutable).
 */
@Repository
public interface AiModificationDecisionRepository extends JpaRepository<AiModificationDecision, UUID> {

    // ─── Paginated queries ────────────────────────────────────────────────────

    Page<AiModificationDecision> findByTenantIdOrderByEvaluatedAtDesc(UUID tenantId, Pageable pageable);

    Page<AiModificationDecision> findByTenantIdAndRouteIdOrderByEvaluatedAtDesc(
            UUID tenantId, UUID routeId, Pageable pageable);

    // ─── Aggregated stats ─────────────────────────────────────────────────────

    /**
     * Returns aggregated AI modification stats for a tenant/route over a time window.
     * Result columns: [total, applied, passthrough, piiScrub, translate, headerRewrite, custom,
     *                  cacheHits, avgLatency, p95Latency, p99Latency]
     */
    @Query(value = """
           SELECT
               COUNT(*)                                                                    AS total,
               COUNT(*) FILTER (WHERE mutation_applied = TRUE)                            AS applied_count,
               COUNT(*) FILTER (WHERE mutation_applied = FALSE)                           AS passthrough_count,
               COUNT(*) FILTER (WHERE mutation_type = 'PII_SCRUB')                       AS pii_scrub_count,
               COUNT(*) FILTER (WHERE mutation_type = 'TRANSLATE')                       AS translate_count,
               COUNT(*) FILTER (WHERE mutation_type = 'HEADER_REWRITE')                  AS header_rewrite_count,
               COUNT(*) FILTER (WHERE mutation_type = 'CUSTOM')                          AS custom_count,
               COUNT(*) FILTER (WHERE cached = TRUE)                                      AS cache_hit_count,
               COALESCE(AVG(latency_ms), 0)                                               AS avg_latency,
               COALESCE(PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY latency_ms), 0)     AS p95_latency,
               COALESCE(PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY latency_ms), 0)     AS p99_latency
           FROM routify_audit.ai_modifier_decision
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

    @Query(value = """
           SELECT
               COUNT(*)                                                                    AS total,
               COUNT(*) FILTER (WHERE mutation_applied = TRUE)                            AS applied_count,
               COUNT(*) FILTER (WHERE mutation_applied = FALSE)                           AS passthrough_count,
               COUNT(*) FILTER (WHERE mutation_type = 'PII_SCRUB')                       AS pii_scrub_count,
               COUNT(*) FILTER (WHERE mutation_type = 'TRANSLATE')                       AS translate_count,
               COUNT(*) FILTER (WHERE mutation_type = 'HEADER_REWRITE')                  AS header_rewrite_count,
               COUNT(*) FILTER (WHERE mutation_type = 'CUSTOM')                          AS custom_count,
               COUNT(*) FILTER (WHERE cached = TRUE)                                      AS cache_hit_count,
               COALESCE(AVG(latency_ms), 0)                                               AS avg_latency,
               COALESCE(PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY latency_ms), 0)     AS p95_latency,
               COALESCE(PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY latency_ms), 0)     AS p99_latency
           FROM routify_audit.ai_modifier_decision
           WHERE tenant_id = :tenantId
             AND evaluated_at >= :from
             AND evaluated_at < :to
           """, nativeQuery = true)
    Object[] getStatsByTenantAndTimeRange(
            @Param("tenantId") UUID    tenantId,
            @Param("from")     Instant from,
            @Param("to")       Instant to);

    // ─── Retention cleanup ────────────────────────────────────────────────────

    @Query("DELETE FROM AiModificationDecision d WHERE d.evaluatedAt < :cutoff")
    @Modifying
    @Transactional
    int deleteByEvaluatedAtBefore(@Param("cutoff") Instant cutoff);
}

