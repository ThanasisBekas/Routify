package io.routify.audit.scheduler;

import io.routify.audit.repository.AiFilterDecisionRepository;
import io.routify.audit.repository.AiModificationDecisionRepository;
import io.routify.audit.repository.AlertEventRepository;
import io.routify.audit.repository.AuditLogRepository;
import io.routify.audit.repository.DlqEventRepository;
import io.routify.audit.repository.RequestLogRepository;
import io.routify.audit.repository.TenantUsageDailyRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Scheduled job that enforces data retention policies across all audit tables.
 *
 * <p>Retention periods are configurable per table via {@code routify.audit.retention.*}
 * properties. Each table is purged independently — a failure purging one table does
 * not prevent cleanup of the others.
 *
 * <p><b>Retention policy summary (defaults):</b>
 * <table>
 *   <tr><th>Table</th><th>Retention</th><th>Property</th></tr>
 *   <tr><td>{@code request_log}</td><td>30 days</td><td>{@code request-log-days}</td></tr>
 *   <tr><td>{@code audit_log}</td><td>90 days</td><td>{@code audit-log-days}</td></tr>
 *   <tr><td>{@code dlq_event}</td><td>30 days</td><td>{@code dlq-log-days}</td></tr>
 *   <tr><td>{@code ai_filter_decision}</td><td>60 days</td><td>{@code ai-filter-decision-days}</td></tr>
 *   <tr><td>{@code ai_modifier_decision}</td><td>60 days</td><td>{@code ai-modifier-decision-days}</td></tr>
 *   <tr><td>{@code alert_event}</td><td>90 days</td><td>{@code alert-event-days}</td></tr>
 *   <tr><td>{@code tenant_usage_daily}</td><td>365 days</td><td>{@code usage-daily-days}</td></tr>
 * </table>
 *
 * <p>Uses batched deletes (default 1,000 rows per batch) to avoid holding full-table
 * locks for extended periods. Each batch runs in its own transaction via
 * {@link TransactionTemplate}, keeping lock duration under ~100ms per batch.
 *
 * <p>Runs nightly at 02:00 UTC to minimise impact on query performance.
 * Micrometer counter {@code routify.audit.retention.purged} tracks total rows purged
 * per table (tagged by {@code table}).
 */
@Slf4j
@Component
public class AuditRetentionScheduler {

    private final AuditLogRepository              auditLogRepository;
    private final RequestLogRepository            requestLogRepository;
    private final DlqEventRepository              dlqEventRepository;
    private final AiFilterDecisionRepository      aiFilterDecisionRepository;
    private final AiModificationDecisionRepository aiModificationDecisionRepository;
    private final AlertEventRepository            alertEventRepository;
    private final TenantUsageDailyRepository      tenantUsageDailyRepository;
    private final TransactionTemplate             txTemplate;

    // Micrometer counters per table
    private final Counter purgedRequestLogs;
    private final Counter purgedAuditLogs;
    private final Counter purgedDlqEvents;
    private final Counter purgedAiFilterDecisions;
    private final Counter purgedAiModifierDecisions;
    private final Counter purgedAlertEvents;
    private final Counter purgedUsageDaily;

    @Value("${routify.audit.retention.request-log-days:30}")
    private int requestLogRetentionDays;

    @Value("${routify.audit.retention.audit-log-days:90}")
    private int auditLogRetentionDays;

    @Value("${routify.audit.retention.dlq-log-days:30}")
    private int dlqLogRetentionDays;

    @Value("${routify.audit.retention.ai-filter-decision-days:60}")
    private int aiFilterDecisionRetentionDays;

    @Value("${routify.audit.retention.ai-modifier-decision-days:60}")
    private int aiModifierDecisionRetentionDays;

    @Value("${routify.audit.retention.alert-event-days:90}")
    private int alertEventRetentionDays;

    @Value("${routify.audit.retention.usage-daily-days:365}")
    private int usageDailyRetentionDays;

    @Value("${routify.audit.retention.batch-size:1000}")
    private int batchSize;

    public AuditRetentionScheduler(
            AuditLogRepository auditLogRepository,
            RequestLogRepository requestLogRepository,
            DlqEventRepository dlqEventRepository,
            AiFilterDecisionRepository aiFilterDecisionRepository,
            AiModificationDecisionRepository aiModificationDecisionRepository,
            AlertEventRepository alertEventRepository,
            TenantUsageDailyRepository tenantUsageDailyRepository,
            TransactionTemplate txTemplate,
            MeterRegistry meterRegistry) {
        this.auditLogRepository = auditLogRepository;
        this.requestLogRepository = requestLogRepository;
        this.dlqEventRepository = dlqEventRepository;
        this.aiFilterDecisionRepository = aiFilterDecisionRepository;
        this.aiModificationDecisionRepository = aiModificationDecisionRepository;
        this.alertEventRepository = alertEventRepository;
        this.tenantUsageDailyRepository = tenantUsageDailyRepository;
        this.txTemplate = txTemplate;

        this.purgedRequestLogs = counter(meterRegistry, "request_log");
        this.purgedAuditLogs = counter(meterRegistry, "audit_log");
        this.purgedDlqEvents = counter(meterRegistry, "dlq_event");
        this.purgedAiFilterDecisions = counter(meterRegistry, "ai_filter_decision");
        this.purgedAiModifierDecisions = counter(meterRegistry, "ai_modifier_decision");
        this.purgedAlertEvents = counter(meterRegistry, "alert_event");
        this.purgedUsageDaily = counter(meterRegistry, "tenant_usage_daily");
    }

    @Scheduled(cron = "${routify.audit.retention.cron:0 0 2 * * *}")
    public void enforceRetention() {
        log.info("Starting audit data retention enforcement (batch-size={})", batchSize);

        Instant requestLogCutoff = Instant.now().minus(requestLogRetentionDays, ChronoUnit.DAYS);
        Instant auditLogCutoff   = Instant.now().minus(auditLogRetentionDays,   ChronoUnit.DAYS);
        Instant dlqLogCutoff     = Instant.now().minus(dlqLogRetentionDays,     ChronoUnit.DAYS);
        Instant aiFilterCutoff   = Instant.now().minus(aiFilterDecisionRetentionDays, ChronoUnit.DAYS);
        Instant aiModifierCutoff = Instant.now().minus(aiModifierDecisionRetentionDays, ChronoUnit.DAYS);
        Instant alertEventCutoff = Instant.now().minus(alertEventRetentionDays, ChronoUnit.DAYS);
        LocalDate usageDailyCutoff = LocalDate.now().minusDays(usageDailyRetentionDays);

        int requestLogsDeleted       = purgeTable("request_log",          requestLogCutoff, purgedRequestLogs,
                (cutoff, bs) -> requestLogRepository.deleteByRequestedAtBefore(cutoff));
        int auditLogsDeleted         = purgeTable("audit_log",            auditLogCutoff, purgedAuditLogs,
                auditLogRepository::deleteBatchByOccurredAtBefore);
        int dlqLogsDeleted           = purgeTable("dlq_event",            dlqLogCutoff, purgedDlqEvents,
                (cutoff, bs) -> dlqEventRepository.deleteByFailedAtBefore(cutoff));
        int aiFilterDeleted          = purgeTable("ai_filter_decision",   aiFilterCutoff, purgedAiFilterDecisions,
                (cutoff, bs) -> aiFilterDecisionRepository.deleteByEvaluatedAtBefore(cutoff));
        int aiModifierDeleted        = purgeTable("ai_modifier_decision", aiModifierCutoff, purgedAiModifierDecisions,
                (cutoff, bs) -> aiModificationDecisionRepository.deleteByEvaluatedAtBefore(cutoff));
        int alertEventsDeleted       = purgeTable("alert_event",          alertEventCutoff, purgedAlertEvents,
                (cutoff, bs) -> alertEventRepository.deleteByOccurredAtBefore(cutoff));
        int usageDailyDeleted        = purgeUsageDaily(usageDailyCutoff);

        log.info("Retention complete: request_log={} ({}d), audit_log={} ({}d), dlq_event={} ({}d), " +
                        "ai_filter={} ({}d), ai_modifier={} ({}d), alert_event={} ({}d), usage_daily={} ({}d)",
                requestLogsDeleted, requestLogRetentionDays,
                auditLogsDeleted,   auditLogRetentionDays,
                dlqLogsDeleted,     dlqLogRetentionDays,
                aiFilterDeleted,    aiFilterDecisionRetentionDays,
                aiModifierDeleted,  aiModifierDecisionRetentionDays,
                alertEventsDeleted, alertEventRetentionDays,
                usageDailyDeleted,  usageDailyRetentionDays);
    }

    /**
     * Deletes records in batches to avoid holding long-running full-table locks.
     * Each batch runs in its own transaction via {@link TransactionTemplate}.
     */
    private int purgeTable(String tableName, Instant cutoff, Counter metric,
                           java.util.function.BiFunction<Instant, Integer, Integer> deleteFn) {
        int totalDeleted = 0;
        try {
            int deleted;
            do {
                Integer result = txTemplate.execute(status -> deleteFn.apply(cutoff, batchSize));
                deleted = result != null ? result : 0;
                totalDeleted += deleted;
                if (deleted > 0) {
                    log.debug("Deleted {} {} entries (total: {})", deleted, tableName, totalDeleted);
                }
            } while (deleted >= batchSize);

            if (totalDeleted > 0) {
                metric.increment(totalDeleted);
            }
        } catch (Exception e) {
            log.error("Failed to purge old {} records: {}", tableName, e.getMessage(), e);
        }
        return totalDeleted;
    }

    /**
     * Purges tenant_usage_daily using a LocalDate cutoff (not Instant).
     */
    private int purgeUsageDaily(LocalDate cutoff) {
        int totalDeleted = 0;
        try {
            Integer result = txTemplate.execute(status ->
                    tenantUsageDailyRepository.deleteByDateBefore(cutoff));
            totalDeleted = result != null ? result : 0;
            if (totalDeleted > 0) {
                purgedUsageDaily.increment(totalDeleted);
                log.debug("Deleted {} tenant_usage_daily entries", totalDeleted);
            }
        } catch (Exception e) {
            log.error("Failed to purge old tenant_usage_daily records: {}", e.getMessage(), e);
        }
        return totalDeleted;
    }

    private static Counter counter(MeterRegistry registry, String table) {
        return Counter.builder("routify.audit.retention.purged")
                .tag("table", table)
                .description("Total rows purged by audit retention scheduler")
                .register(registry);
    }
}
