package io.routify.audit.scheduler;

import io.routify.audit.domain.TenantUsageDaily;
import io.routify.audit.repository.RequestLogRepository;
import io.routify.audit.repository.TenantUsageDailyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Scheduler that aggregates daily tenant usage metrics from the request log
 * and persists them into the {@code tenant_usage_daily} table.
 *
 * <p>Runs at 00:05 UTC each day. For each distinct tenant that had traffic
 * yesterday, creates or updates a snapshot row with request count and error count.
 *
 * <p>Route and filter counts are not tracked here (they are live counts fetched
 * directly from route-service via RabbitMQ when the admin-api serves the usage endpoint).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UsageSnapshotScheduler {

    private final RequestLogRepository requestLogRepository;
    private final TenantUsageDailyRepository usageRepository;

    /**
     * Aggregate yesterday's request metrics per tenant and persist daily snapshots.
     */
    @Scheduled(cron = "0 5 0 * * *", zone = "UTC")
    @Transactional
    public void snapshotDailyUsage() {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        Instant dayStart = yesterday.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant dayEnd = yesterday.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);

        log.info("UsageSnapshotScheduler: aggregating usage for date={}", yesterday);

        List<Object[]> stats = requestLogRepository.countRequestsByTenantForPeriod(dayStart, dayEnd);

        int snapshotCount = 0;
        for (Object[] row : stats) {
            UUID tenantId = (UUID) row[0];
            long requestCount = ((Number) row[1]).longValue();
            long errorCount = ((Number) row[2]).longValue();

            var existing = usageRepository.findByTenantIdAndDate(tenantId, yesterday);
            if (existing.isPresent()) {
                var usage = existing.get();
                usage.setRequestCount(requestCount);
                usage.setErrorCount(errorCount);
                usage.setSnapshotAt(Instant.now());
                usageRepository.save(usage);
            } else {
                usageRepository.save(new TenantUsageDaily(
                        tenantId, yesterday, 0, 0, requestCount, errorCount));
            }
            snapshotCount++;
        }

        log.info("UsageSnapshotScheduler: persisted {} daily snapshots for date={}", snapshotCount, yesterday);
    }
}

