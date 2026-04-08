package io.routify.audit.repository;

import io.routify.audit.domain.AlertRule;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AlertRuleRepository extends JpaRepository<AlertRule, UUID> {

    List<AlertRule> findByEnabledTrue();

    Page<AlertRule> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

    Optional<AlertRule> findByIdAndTenantId(UUID id, UUID tenantId);
}

