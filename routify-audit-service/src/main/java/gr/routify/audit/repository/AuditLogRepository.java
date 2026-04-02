package gr.routify.audit.repository;

import gr.routify.audit.domain.AuditLogEntry;
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
public interface AuditLogRepository extends JpaRepository<AuditLogEntry, UUID> {

    Page<AuditLogEntry> findByTenantIdOrderByOccurredAtDesc(UUID tenantId, Pageable pageable);

    Page<AuditLogEntry> findByTenantIdAndEventTypeOrderByOccurredAtDesc(
            UUID tenantId, String eventType, Pageable pageable);

    Page<AuditLogEntry> findByTenantIdAndAggregateTypeAndAggregateIdOrderByOccurredAtDesc(
            UUID tenantId, String aggregateType, String aggregateId, Pageable pageable);

    @Query("""
            SELECT a FROM AuditLogEntry a
            WHERE a.tenantId = :tenantId
            AND a.occurredAt BETWEEN :from AND :to
            ORDER BY a.occurredAt DESC
            """)
    Page<AuditLogEntry> findByTenantAndTimeRange(
            @Param("tenantId") UUID tenantId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);

    @Query("""
            SELECT a FROM AuditLogEntry a
            WHERE a.tenantId = :tenantId
            AND a.aggregateType = :type
            AND a.aggregateId = :id
            ORDER BY a.occurredAt DESC
            """)
    List<AuditLogEntry> findAggregateHistory(
            @Param("tenantId") UUID tenantId,
            @Param("type") String aggregateType,
            @Param("id") String aggregateId);

    long countByTenantIdAndOccurredAtBetween(UUID tenantId, Instant from, Instant to);

    /** Efficient bulk delete for retention — leverages partition pruning on occurred_at. */
    @Modifying
    @Query(value = "DELETE FROM routify_audit.audit_log WHERE occurred_at < :cutoff", nativeQuery = true)
    int deleteByOccurredAtBefore(@Param("cutoff") Instant cutoff);

    /**
     * Phase 4.7: Batched delete — deletes up to {@code batchSize} rows older than cutoff.
     * Call in a loop until 0 rows are affected to avoid holding full-table locks.
     */
    @Modifying
    @Query(value = """
            DELETE FROM routify_audit.audit_log
            WHERE id IN (
                SELECT id FROM routify_audit.audit_log
                WHERE occurred_at < :cutoff
                LIMIT :batchSize
            )
            """, nativeQuery = true)
    int deleteBatchByOccurredAtBefore(@Param("cutoff") Instant cutoff,
                                       @Param("batchSize") int batchSize);
}



