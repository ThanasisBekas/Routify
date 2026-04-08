package io.routify.audit.repository;

import io.routify.audit.domain.AlertEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface AlertEventRepository extends JpaRepository<AlertEvent, UUID> {

    Page<AlertEvent> findByRuleIdOrderByOccurredAtDesc(UUID ruleId, Pageable pageable);

    Page<AlertEvent> findByTenantIdOrderByOccurredAtDesc(UUID tenantId, Pageable pageable);

    /** Bulk-delete alert event history older than the given cutoff (called by retention scheduler). */
    @Modifying
    @Query(value = "DELETE FROM routify_audit.alert_event WHERE occurred_at < :cutoff", nativeQuery = true)
    int deleteByOccurredAtBefore(@Param("cutoff") Instant cutoff);
}

