package io.routify.audit.repository;

import io.routify.audit.domain.TenantUsageDaily;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenantUsageDailyRepository extends JpaRepository<TenantUsageDaily, UUID> {

    Optional<TenantUsageDaily> findByTenantIdAndDate(UUID tenantId, LocalDate date);

    List<TenantUsageDaily> findByTenantIdAndDateBetweenOrderByDateDesc(
            UUID tenantId, LocalDate from, LocalDate to);

    @Query("""
            SELECT DISTINCT u.tenantId
            FROM TenantUsageDaily u
            WHERE u.date = :date
            """)
    List<UUID> findTenantIdsWithSnapshotForDate(@Param("date") LocalDate date);
}

