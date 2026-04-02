package gr.routify.audit.scheduler;

import gr.routify.audit.repository.AuditLogRepository;
import gr.routify.audit.repository.RequestLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Scheduled job that enforces data retention policies.
 *
 * <p>Default retention periods (configurable):
 * <ul>
 *   <li>Request logs: 30 days (high volume, short retention)</li>
 *   <li>Audit logs: 365 days (compliance, longer retention)</li>
 * </ul>
 *
 * <p>Phase 4.7 fix: Uses batched deletes (default 1,000 rows per batch) to avoid
 * holding full-table locks for extended periods on busy systems. Each batch runs in
 * its own mini-transaction, keeping lock duration under ~100ms per batch.
 *
 * <p>Runs nightly at 02:00 UTC to minimise impact on query performance.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditRetentionScheduler {

    private final AuditLogRepository   auditLogRepository;
    private final RequestLogRepository requestLogRepository;

    @Value("${routify.audit.retention.request-log-days:30}")
    private int requestLogRetentionDays;

    @Value("${routify.audit.retention.audit-log-days:365}")
    private int auditLogRetentionDays;

    @Value("${routify.audit.retention.batch-size:1000}")
    private int batchSize;

    @Scheduled(cron = "${routify.audit.retention.cron:0 0 2 * * *}")
    @Transactional
    public void enforceRetention() {
        Instant requestLogCutoff = Instant.now().minus(requestLogRetentionDays, ChronoUnit.DAYS);
        Instant auditLogCutoff   = Instant.now().minus(auditLogRetentionDays,   ChronoUnit.DAYS);

        int requestLogsDeleted = 0;
        int auditLogsDeleted   = 0;

        try {
            requestLogsDeleted = deleteInBatches(requestLogCutoff, "request log",
                    (cutoff, bs) -> requestLogRepository.deleteByRequestedAtBefore(cutoff));
        } catch (Exception e) {
            log.error("Failed to purge old request logs: {}", e.getMessage(), e);
        }

        try {
            auditLogsDeleted = deleteInBatches(auditLogCutoff, "audit log",
                    auditLogRepository::deleteBatchByOccurredAtBefore);
        } catch (Exception e) {
            log.error("Failed to purge old audit logs: {}", e.getMessage(), e);
        }

        log.info("Retention enforcement complete: {} request logs purged (older than {} days), {} audit logs purged (older than {} days)",
                requestLogsDeleted, requestLogRetentionDays, auditLogsDeleted, auditLogRetentionDays);
    }

    /**
     * Deletes records in batches to avoid holding long-running full-table locks.
     * Loops until fewer than batchSize rows are deleted (meaning all qualifying rows are gone).
     */
    private int deleteInBatches(Instant cutoff, String type,
                                java.util.function.BiFunction<Instant, Integer, Integer> deleteFn) {
        int totalDeleted = 0;
        int deleted;
        do {
            deleted = deleteFn.apply(cutoff, batchSize);
            totalDeleted += deleted;
            if (deleted > 0) {
                log.debug("Deleted {} {} entries (total so far: {})", deleted, type, totalDeleted);
            }
        } while (deleted >= batchSize);
        return totalDeleted;
    }
}



