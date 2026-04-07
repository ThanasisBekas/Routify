package io.routify.audit.repository;

import io.routify.audit.domain.AlertEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface AlertEventRepository extends JpaRepository<AlertEvent, UUID> {

    Page<AlertEvent> findByRuleIdOrderByOccurredAtDesc(UUID ruleId, Pageable pageable);

    Page<AlertEvent> findByTenantIdOrderByOccurredAtDesc(UUID tenantId, Pageable pageable);
}

