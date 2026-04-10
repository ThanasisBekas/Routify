package io.routify.audit.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.routify.audit.domain.AiFilterDecision;
import io.routify.audit.domain.AiPromptVersion;
import io.routify.audit.domain.AlertEvent;
import io.routify.audit.domain.AlertRule;
import io.routify.audit.domain.AuditLogEntry;
import io.routify.audit.domain.RequestLog;
import io.routify.audit.replay.FailedRequestReplayService;
import io.routify.audit.repository.AiFilterDecisionRepository;
import io.routify.audit.repository.AiPromptVersionRepository;
import io.routify.audit.repository.AlertEventRepository;
import io.routify.audit.repository.AlertRuleRepository;
import io.routify.audit.repository.AuditLogRepository;
import io.routify.audit.repository.RequestLogRepository;
import io.routify.audit.repository.TenantUsageDailyRepository;
import io.routify.common.event.CommandEvent;
import io.routify.common.event.KafkaTopics;
import io.routify.common.event.QueryRequest;
import io.routify.common.event.QueryResponse;
import io.routify.common.event.RabbitTopology;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RabbitMQ request/reply handler for routify-audit-service.
 *
 * <p>All request bodies are deserialised into strongly-typed {@link QueryRequest} records
 * by the Jackson2JsonMessageConverter in the listener container.
 * Return values are serialised back to JSON automatically by the same converter.
 * Audit data is immutable — only replay commands mutate state.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditRabbitHandler {

    private final AuditLogRepository         auditLogRepository;
    private final RequestLogRepository       requestLogRepository;
    private final AiFilterDecisionRepository aiFilterDecisionRepository;
    private final AiPromptVersionRepository  aiPromptVersionRepository;
    private final TenantUsageDailyRepository tenantUsageDailyRepository;
    private final AlertRuleRepository        alertRuleRepository;
    private final AlertEventRepository       alertEventRepository;
    private final FailedRequestReplayService replayService;
    private final ObjectMapper               objectMapper;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    // ─── Audit Event Queries ──────────────────────────────────────────────────

    @RabbitListener(queues = RabbitTopology.QUEUE_AUDIT_EVENTS_QUERY)
    public QueryResponse.AuditEventsPage handleAuditEventsQuery(QueryRequest.AuditEventsQuery req) {
        log.debug("RabbitMQ: received audit.events.query request");
        var pageable = PageRequest.of(req.page(), req.size(),
                Sort.by(Sort.Direction.DESC, "occurredAt"));

        Page<AuditLogEntry> result;
        if (req.from() != null && req.to() != null) {
            result = auditLogRepository.findByTenantAndTimeRange(
                    req.tenantId(), Instant.parse(req.from()), Instant.parse(req.to()), pageable);
        } else if (req.eventType() != null) {
            result = auditLogRepository.findByTenantIdAndEventTypeOrderByOccurredAtDesc(
                    req.tenantId(), req.eventType(), pageable);
        } else if (req.aggregateType() != null && req.aggregateId() != null) {
            result = auditLogRepository.findByTenantIdAndAggregateTypeAndAggregateIdOrderByOccurredAtDesc(
                    req.tenantId(), req.aggregateType(), req.aggregateId(), pageable);
        } else {
            result = auditLogRepository.findByTenantIdOrderByOccurredAtDesc(req.tenantId(), pageable);
        }

        var content = result.getContent().stream().map(this::toAuditEntry).toList();
        return new QueryResponse.AuditEventsPage(content, result.getTotalElements(),
                result.getTotalPages(), result.getNumber(), result.getSize());
    }

    // ─── Request Log Queries ──────────────────────────────────────────────────

    @RabbitListener(queues = RabbitTopology.QUEUE_AUDIT_REQUESTS_QUERY)
    public QueryResponse.RequestLogsPage handleRequestsQuery(QueryRequest.AuditRequestsQuery req) {
        log.debug("RabbitMQ: received audit.requests.query request");
        var pageable = PageRequest.of(req.page(), req.size(),
                Sort.by(Sort.Direction.DESC, "requestedAt"));

        Page<RequestLog> result;
        if (req.routeId() != null) {
            result = requestLogRepository.findByTenantIdAndRouteIdOrderByRequestedAtDesc(
                    req.tenantId(), req.routeId(), pageable);
        } else if (req.from() != null && req.to() != null) {
            result = requestLogRepository.findByTenantAndTimeRange(
                    req.tenantId(), Instant.parse(req.from()), Instant.parse(req.to()), pageable);
        } else {
            result = requestLogRepository.findByTenantIdOrderByRequestedAtDesc(req.tenantId(), pageable);
        }

        var content = result.getContent().stream().map(this::toRequestLogEntry).toList();
        return new QueryResponse.RequestLogsPage(content, result.getTotalElements(),
                result.getTotalPages(), result.getNumber(), result.getSize());
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_AUDIT_REQUESTS_STATS)
    public QueryResponse.RequestStatsResult handleRequestStats(QueryRequest.AuditRequestStats req) {
        log.debug("RabbitMQ: received audit.requests.stats request");
        Instant since = Instant.now().minusSeconds(86400);
        Object[] stats = requestLogRepository.getRouteStats(req.tenantId(), req.routeId(), since);

        long   totalRequests = 0L;
        double avgDurationMs = 0.0;
        long   maxDurationMs = 0L;
        long   errorCount    = 0L;

        if (stats != null && stats[0] != null) {
            totalRequests = ((Number) stats[0]).longValue();
            avgDurationMs = stats[1] != null ? ((Number) stats[1]).doubleValue() : 0.0;
            maxDurationMs = stats[2] != null ? ((Number) stats[2]).longValue() : 0L;
            errorCount    = stats[3] != null ? ((Number) stats[3]).longValue() : 0L;
        }
        return new QueryResponse.RequestStatsResult(
                req.routeId(), totalRequests, avgDurationMs, maxDurationMs, errorCount);
    }

    // ─── Replay Queries ───────────────────────────────────────────────────────

    @RabbitListener(queues = RabbitTopology.QUEUE_AUDIT_REPLAY_FAILED_QUERY)
    public QueryResponse.RequestLogsPage handleReplayFailedQuery(QueryRequest.ReplayFailedQuery req) {
        log.debug("RabbitMQ: received audit.replay.failed.query request");
        var pageable = PageRequest.of(req.page(), req.size(),
                Sort.by(Sort.Direction.DESC, "requestedAt"));
        Page<RequestLog> result = req.routeId() != null
                ? requestLogRepository.findFailedByTenantIdAndRouteId(
                        req.tenantId(), req.routeId(), pageable)
                : requestLogRepository.findFailedByTenantId(req.tenantId(), pageable);

        var content = result.getContent().stream().map(this::toRequestLogEntry).toList();
        return new QueryResponse.RequestLogsPage(content, result.getTotalElements(),
                result.getTotalPages(), result.getNumber(), result.getSize());
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_AUDIT_REPLAY_PENDING_QUERY)
    public QueryResponse.RequestLogsPage handleReplayPendingQuery(QueryRequest.ReplayPendingQuery req) {
        log.debug("RabbitMQ: received audit.replay.pending.query request");
        var pageable = PageRequest.of(req.page(), req.size(),
                Sort.by(Sort.Direction.DESC, "requestedAt"));
        Page<RequestLog> result = req.routeId() != null
                ? requestLogRepository.findPendingReplayByTenantIdAndRouteId(
                        req.tenantId(), req.routeId(), pageable)
                : requestLogRepository.findPendingReplayByTenantId(req.tenantId(), pageable);

        var content = result.getContent().stream().map(this::toRequestLogEntry).toList();
        return new QueryResponse.RequestLogsPage(content, result.getTotalElements(),
                result.getTotalPages(), result.getNumber(), result.getSize());
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_AUDIT_REPLAY_STATS)
    public QueryResponse.ReplayStatsResult handleReplayStats(QueryRequest.ReplayStats req) {
        log.debug("RabbitMQ: received audit.replay.stats request");
        List<Object[]> rows = requestLogRepository.countFailedByReplayStatus(req.tenantId());

        long pending = 0L, inProgress = 0L, succeeded = 0L, failed = 0L, skipped = 0L;
        for (Object[] row : rows) {
            String dbKey = row[0] != null ? String.valueOf(row[0]) : null;
            long   count = ((Number) row[1]).longValue();
            switch (dbKey != null ? dbKey : "") {
                case "PENDING"     -> pending    = count;
                case "IN_PROGRESS" -> inProgress = count;
                case "SUCCEEDED"   -> succeeded  = count;
                case "FAILED"      -> failed     = count;
                case "SKIPPED"     -> skipped    = count;
            }
        }
        return new QueryResponse.ReplayStatsResult(pending, inProgress, succeeded, failed, skipped);
    }

    // ─── Replay Commands ──────────────────────────────────────────────────────

    @RabbitListener(queues = RabbitTopology.QUEUE_AUDIT_REPLAY_SINGLE)
    public QueryResponse.ReplaySingleResult handleReplaySingle(QueryRequest.ReplaySingle req) {
        log.debug("RabbitMQ: received audit.replay.single command");
        FailedRequestReplayService.ReplayResult result = replayService.replay(req.id(), req.tenantId());
        var response = new QueryResponse.ReplaySingleResult(
                result.requestLogId(), result.outcome().name(),
                result.responseStatus(), result.message());
        publishReplayEvent("REPLAY_COMPLETED", req.tenantId(), response);
        return response;
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_AUDIT_REPLAY_BULK)
    public QueryResponse.ReplayBulkResult handleReplayBulk(QueryRequest.ReplayBulk req) {
        log.debug("RabbitMQ: received audit.replay.bulk command");
        FailedRequestReplayService.BulkReplayResult result =
                replayService.replayAll(req.tenantId(), req.limit());
        var response = new QueryResponse.ReplayBulkResult(
                result.total(), result.succeeded(), result.failed(), result.skipped());
        publishReplayEvent("REPLAY_BULK_COMPLETED", req.tenantId(), response);
        return response;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    // ─── Route Health Stats (Gateway Health Dashboard v2) ─────────────────────

    /**
     * Returns per-route health stats (latency percentiles, error rate, status code distribution)
     * for the Gateway Health Dashboard v2 heatmap.
     */
    @RabbitListener(queues = RabbitTopology.QUEUE_AUDIT_ROUTE_HEALTH)
    public QueryResponse.RouteHealthResponse handleRouteHealth(QueryRequest.RouteHealthQuery req) {
        log.debug("RabbitMQ: received audit.route.health request: tenantId={} window={}",
                req.tenantId(), req.window());

        Instant since = switch (req.window() != null ? req.window() : "24h") {
            case "1h" -> Instant.now().minusSeconds(3600);
            case "7d" -> Instant.now().minusSeconds(7 * 86400);
            default   -> Instant.now().minusSeconds(86400);
        };

        List<Object[]> rows = requestLogRepository.getRouteHealthStats(req.tenantId(), since);

        var entries = rows.stream().map(row -> {
            UUID   routeId       = row[0] != null ? UUID.fromString(row[0].toString()) : null;
            String routeName     = row[1] != null ? row[1].toString() : "unknown";
            long   totalRequests = row[2] != null ? ((Number) row[2]).longValue() : 0L;
            long   errorCount    = row[3] != null ? ((Number) row[3]).longValue() : 0L;
            double errorRate     = totalRequests > 0 ? (double) errorCount / totalRequests : 0.0;
            double avgLatencyMs  = row[4] != null ? ((Number) row[4]).doubleValue() : 0.0;
            double p50LatencyMs  = row[5] != null ? ((Number) row[5]).doubleValue() : 0.0;
            double p95LatencyMs  = row[6] != null ? ((Number) row[6]).doubleValue() : 0.0;
            double p99LatencyMs  = row[7] != null ? ((Number) row[7]).doubleValue() : 0.0;

            // Status code distribution for this route
            List<Object[]> statusRows = requestLogRepository
                    .getStatusCodeDistribution(req.tenantId(), routeId, since);
            Map<Integer, Long> statusDist = new HashMap<>();
            for (Object[] s : statusRows) {
                statusDist.put(((Number) s[0]).intValue(), ((Number) s[1]).longValue());
            }

            return new QueryResponse.RouteHealthResponse.RouteHealthEntry(
                    routeId, routeName, totalRequests, errorCount, errorRate,
                    p50LatencyMs, p95LatencyMs, p99LatencyMs, avgLatencyMs, statusDist);
        }).toList();

        return new QueryResponse.RouteHealthResponse(entries);
    }

    // ─── AI Filter Stats & Decision Log ───────────────────────────────────────

    /**
     * Returns aggregated AI filter statistics for a tenant (optionally scoped to a route)
     * over a configurable time window.
     *
     * <p>Called by routify-admin-api for the Dashboard's AI Filter Stats page.
     */
    @RabbitListener(queues = RabbitTopology.QUEUE_AUDIT_AI_FILTER_STATS)
    public QueryResponse.AiFilterStatsResult handleAiFilterStats(QueryRequest.AiFilterStatsQuery req) {
        log.debug("RabbitMQ: received audit.ai-filter.stats request: tenantId={} routeId={}",
                req.tenantId(), req.routeId());

        Instant from = req.from() != null ? Instant.parse(req.from()) : Instant.now().minusSeconds(86400);
        Instant to   = req.to()   != null ? Instant.parse(req.to())   : Instant.now();

        Object[] row = req.routeId() != null
                ? aiFilterDecisionRepository.getStatsByTenantAndRouteAndTimeRange(
                        req.tenantId(), req.routeId(), from, to)
                : req.promptVersionId() != null
                        ? aiFilterDecisionRepository.getStatsByTenantAndVersionAndTimeRange(
                                req.tenantId(), req.promptVersionId(), from, to)
                        : aiFilterDecisionRepository.getStatsByTenantAndTimeRange(
                                req.tenantId(), from, to);

        long   total      = 0L;
        long   allowCount = 0L;
        long   blockCount = 0L;
        long   flagCount  = 0L;
        long   fallback   = 0L;
        long   cacheHits  = 0L;
        double avgLatency = 0.0;
        long   p95Latency = 0L;
        long   p99Latency = 0L;

        if (row != null && row[0] != null) {
            total      = ((Number) row[0]).longValue();
            allowCount = row[1] != null ? ((Number) row[1]).longValue()  : 0L;
            blockCount = row[2] != null ? ((Number) row[2]).longValue()  : 0L;
            flagCount  = row[3] != null ? ((Number) row[3]).longValue()  : 0L;
            fallback   = row[4] != null ? ((Number) row[4]).longValue()  : 0L;
            cacheHits  = row[5] != null ? ((Number) row[5]).longValue()  : 0L;
            avgLatency = row[6] != null ? ((Number) row[6]).doubleValue(): 0.0;
            p95Latency = row[7] != null ? ((Number) row[7]).longValue()  : 0L;
            p99Latency = row[8] != null ? ((Number) row[8]).longValue()  : 0L;
        }

        return new QueryResponse.AiFilterStatsResult(
                req.tenantId(), req.routeId(),
                total, allowCount, blockCount, flagCount, fallback, cacheHits,
                avgLatency, p95Latency, p99Latency,
                from.toString(), to.toString());
    }

    /**
     * Returns a paginated AI filter decision log for the dashboard's decision audit view.
     * Supports optional filtering by routeId, action, and time range.
     */
    @RabbitListener(queues = RabbitTopology.QUEUE_AUDIT_AI_FILTER_QUERY)
    public QueryResponse.AiFilterDecisionsPage handleAiFilterDecisionsQuery(
            QueryRequest.AiFilterDecisionsQuery req) {
        log.debug("RabbitMQ: received audit.ai-filter.query request: tenantId={} routeId={} action={}",
                req.tenantId(), req.routeId(), req.action());

        var pageable = PageRequest.of(req.page(), Math.min(req.size(), 100),
                Sort.by(Sort.Direction.DESC, "evaluatedAt"));

        Page<AiFilterDecision> result;
        if (req.from() != null && req.to() != null) {
            Instant from = Instant.parse(req.from());
            Instant to   = Instant.parse(req.to());
            result = req.routeId() != null
                    ? aiFilterDecisionRepository.findByTenantAndRouteAndTimeRange(
                            req.tenantId(), req.routeId(), from, to, pageable)
                    : aiFilterDecisionRepository.findByTenantAndTimeRange(
                            req.tenantId(), from, to, pageable);
        } else if (req.routeId() != null && req.action() != null) {
            result = aiFilterDecisionRepository
                    .findByTenantIdAndRouteIdAndActionOrderByEvaluatedAtDesc(
                            req.tenantId(), req.routeId(), req.action().toUpperCase(), pageable);
        } else if (req.routeId() != null) {
            result = aiFilterDecisionRepository
                    .findByTenantIdAndRouteIdOrderByEvaluatedAtDesc(
                            req.tenantId(), req.routeId(), pageable);
        } else if (req.action() != null) {
            result = aiFilterDecisionRepository
                    .findByTenantIdAndActionOrderByEvaluatedAtDesc(
                            req.tenantId(), req.action().toUpperCase(), pageable);
        } else {
            result = aiFilterDecisionRepository
                    .findByTenantIdOrderByEvaluatedAtDesc(req.tenantId(), pageable);
        }

        List<QueryResponse.AiFilterDecisionsPage.AiFilterDecisionEntry> content =
                result.getContent().stream().map(this::toDecisionEntry).toList();

        return new QueryResponse.AiFilterDecisionsPage(
                content, result.getTotalElements(),
                result.getTotalPages(), result.getNumber(), result.getSize());
    }

    private QueryResponse.AiFilterDecisionsPage.AiFilterDecisionEntry toDecisionEntry(AiFilterDecision d) {
        return new QueryResponse.AiFilterDecisionsPage.AiFilterDecisionEntry(
                d.getEvaluationId(), d.getRouteId(), d.getRouteName(), d.getTenantId(),
                d.getAction(), d.getReason(), d.getConfidence(), d.isCached(),
                d.getEvaluationMode(), d.getLatencyMs() != null ? d.getLatencyMs() : 0L,
                d.getMethod(), d.getPath(), d.getClientIp(), d.getEvaluatedAt());
    }

    private void publishReplayEvent(String eventType, UUID tenantId, Object data) {
        try {
            Map<String, Object> event = new HashMap<>(objectMapper.convertValue(
                    data, new com.fasterxml.jackson.core.type.TypeReference<>() {}));
            event.put("eventType",  eventType);
            event.put("tenantId",   tenantId != null ? tenantId.toString() : null);
            event.put("occurredAt", Instant.now().toString());
            kafkaTemplate.send(KafkaTopics.AUDIT_EVENTS,
                    tenantId != null ? tenantId.toString() : "",
                    objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.warn("Failed to publish replay event to Kafka: {}", e.getMessage());
        }
    }

    private QueryResponse.AuditEventsPage.AuditEventEntry toAuditEntry(AuditLogEntry e) {
        return new QueryResponse.AuditEventsPage.AuditEventEntry(
                e.getId(), e.getTenantId(), e.getEventType(), e.getAggregateType(),
                e.getAggregateId(), e.getActorId(), e.getCorrelationId(),
                e.getOccurredAt(), e.getRecordedAt());
    }

    private QueryResponse.RequestLogsPage.RequestLogEntry toRequestLogEntry(RequestLog r) {
        return new QueryResponse.RequestLogsPage.RequestLogEntry(
                r.getId(), r.getTenantId(), r.getRouteId(), r.getRouteName(),
                r.getCorrelationId(), r.getHttpMethod(), r.getPath(), r.getQueryString(),
                r.getUpstreamUri(), r.getResponseStatus(), r.getDurationMs(),
                r.getClientIp(), r.getUserId(), r.getErrorMessage(), r.isFailed(),
                r.getRequestHeaders(), r.getResponseHeaders(),
                r.getRequestBody(), r.getResponseBody(),
                r.getReplayStatus(), r.getReplayCount(), r.getReplayedAt(),
                r.getReplayResponseStatus(), r.getReplayError(), r.getRequestedAt());
    }

    // ─── Tenant Usage Analytics ──────────────────────────────────────────────

    @RabbitListener(queues = RabbitTopology.QUEUE_AUDIT_USAGE_HISTORY)
    public QueryResponse.UsageHistoryResult handleUsageHistory(QueryRequest.UsageHistory req) {
        log.debug("RabbitMQ: received usage history request: tenantId={} days={}", req.tenantId(), req.days());
        int days = req.days() > 0 ? req.days() : 30;
        var to = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1);
        var from = to.minusDays(days - 1);

        // 1. Try pre-aggregated daily snapshots (populated by UsageSnapshotScheduler at 00:05 UTC)
        var entries = tenantUsageDailyRepository
                .findByTenantIdAndDateBetweenOrderByDateDesc(req.tenantId(), from, to)
                .stream()
                .map(u -> new QueryResponse.UsageHistoryResult.DailyUsage(
                        u.getDate().toString(), u.getRouteCount(), u.getFilterCount(),
                        u.getRequestCount(), u.getErrorCount()))
                .toList();

        // 2. Fallback: if no snapshots exist yet, aggregate live from request_log.
        //    This covers the case where the nightly scheduler hasn't run yet (e.g. fresh setup / dev).
        if (entries.isEmpty()) {
            log.debug("No pre-aggregated usage snapshots found for tenant={}, falling back to live aggregation", req.tenantId());
            var toDate = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
            var fromDate = toDate.minusDays(days);
            Instant dayStart = fromDate.atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
            Instant dayEnd = toDate.plusDays(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC);

            entries = requestLogRepository.countDailyRequestsForTenant(req.tenantId(), dayStart, dayEnd)
                    .stream()
                    .map(row -> new QueryResponse.UsageHistoryResult.DailyUsage(
                            row[0].toString(),
                            0, 0,
                            ((Number) row[1]).longValue(),
                            ((Number) row[2]).longValue()))
                    .toList();
        }

        return new QueryResponse.UsageHistoryResult(req.tenantId(), entries);
    }

    // ─── AI Prompt Version Management ─────────────────────────────────────────

    @RabbitListener(queues = RabbitTopology.QUEUE_AI_PROMPT_VERSIONS_QUERY)
    public QueryResponse.PromptVersionsPage handlePromptVersionsQuery(QueryRequest.PromptVersionsQuery req) {
        log.debug("RabbitMQ: received ai-prompt.versions.query: filterId={}", req.filterId());
        var pageable = PageRequest.of(req.page(), req.size());
        var result = aiPromptVersionRepository.findByFilterIdAndTenantIdOrderByVersionDesc(
                req.filterId(), req.tenantId(), pageable);
        var content = result.getContent().stream().map(this::toVersionSummary).toList();
        return new QueryResponse.PromptVersionsPage(content, result.getTotalElements(),
                result.getTotalPages(), result.getNumber(), result.getSize());
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_AI_PROMPT_VERSIONS_GET)
    public QueryResponse.PromptVersionDetail handlePromptVersionGet(QueryRequest.PromptVersionGet req) {
        log.debug("RabbitMQ: received ai-prompt.versions.get: id={}", req.id());
        var version = aiPromptVersionRepository.findByIdAndTenantId(req.id(), req.tenantId())
                .orElseThrow(() -> new io.routify.common.exception.RoutifyException.NotFound(
                        "PromptVersion", req.id().toString()));
        return toVersionDetail(version);
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_AI_PROMPT_VERSIONS_SAVE)
    public QueryResponse.PromptVersionDetail handlePromptVersionSave(QueryRequest.PromptVersionSave req) {
        log.debug("RabbitMQ: received ai-prompt.versions.save: action={} filterId={}", req.action(), req.filterId());
        return switch (req.action()) {
            case "CREATE_DRAFT" -> createDraftVersion(req);
            case "ACTIVATE"     -> activateVersion(req);
            case "ARCHIVE"      -> archiveVersion(req);
            default -> throw new io.routify.common.exception.RoutifyException.BadRequest(
                    "Unknown prompt version action: " + req.action());
        };
    }

    private QueryResponse.PromptVersionDetail createDraftVersion(QueryRequest.PromptVersionSave req) {
        int nextVersion = aiPromptVersionRepository.findMaxVersionByFilterIdAndTenantId(
                req.filterId(), req.tenantId()) + 1;
        var version = AiPromptVersion.builder()
                .filterId(req.filterId())
                .tenantId(req.tenantId())
                .version(nextVersion)
                .promptText(req.promptText())
                .description(req.description())
                .status("DRAFT")
                .totalDecisions(0)
                .correctCount(0)
                .createdBy(req.requestedBy())
                .build();
        return toVersionDetail(aiPromptVersionRepository.save(version));
    }

    private QueryResponse.PromptVersionDetail activateVersion(QueryRequest.PromptVersionSave req) {
        var version = aiPromptVersionRepository.findByIdAndTenantId(req.versionId(), req.tenantId())
                .orElseThrow(() -> new io.routify.common.exception.RoutifyException.NotFound(
                        "PromptVersion", req.versionId().toString()));
        if ("ARCHIVED".equals(version.getStatus())) {
            throw new io.routify.common.exception.RoutifyException.Validation(
                    "Cannot activate an archived version");
        }
        // Archive the currently active version (if any)
        aiPromptVersionRepository.findByFilterIdAndTenantIdAndStatus(
                req.filterId(), req.tenantId(), "ACTIVE")
                .ifPresent(active -> {
                    active.setStatus("ARCHIVED");
                    active.setArchivedAt(Instant.now());
                    aiPromptVersionRepository.save(active);
                });
        // Activate the requested version
        version.setStatus("ACTIVE");
        version.setActivatedAt(Instant.now());
        var saved = aiPromptVersionRepository.save(version);

        // Publish UpdateFilter Kafka command to update the filter's config.policy
        publishFilterConfigUpdate(req.filterId(), req.tenantId(), saved.getPromptText(), req.requestedBy());

        return toVersionDetail(saved);
    }

    private QueryResponse.PromptVersionDetail archiveVersion(QueryRequest.PromptVersionSave req) {
        var version = aiPromptVersionRepository.findByIdAndTenantId(req.versionId(), req.tenantId())
                .orElseThrow(() -> new io.routify.common.exception.RoutifyException.NotFound(
                        "PromptVersion", req.versionId().toString()));
        version.setStatus("ARCHIVED");
        version.setArchivedAt(Instant.now());
        return toVersionDetail(aiPromptVersionRepository.save(version));
    }

    private void publishFilterConfigUpdate(UUID filterId, UUID tenantId, String promptText, String requestedBy) {
        try {
            var config = Map.<String, Object>of("policy", promptText);
            var command = new CommandEvent.UpdateFilter(
                    UUID.randomUUID(), tenantId, requestedBy, Instant.now(),
                    filterId, null, null, config, null);
            kafkaTemplate.send(KafkaTopics.FILTER_COMMANDS,
                    tenantId.toString(), objectMapper.writeValueAsString(command));
            log.info("Published UpdateFilter command for filter={} after prompt activation", filterId);
        } catch (Exception e) {
            log.error("Failed to publish UpdateFilter for prompt activation: {}", e.getMessage(), e);
        }
    }

    // ─── AI Decision Labelling ────────────────────────────────────────────────

    @RabbitListener(queues = RabbitTopology.QUEUE_AI_DECISION_LABEL)
    @org.springframework.transaction.annotation.Transactional
    public QueryResponse.AiDecisionLabelResult handleAiDecisionLabel(QueryRequest.AiDecisionLabel req) {
        log.debug("RabbitMQ: received ai-decision.label: evaluationId={} label={}", req.evaluationId(), req.label());
        var decision = aiFilterDecisionRepository.findByEvaluationIdAndTenantId(
                req.evaluationId(), req.tenantId())
                .orElseThrow(() -> new io.routify.common.exception.RoutifyException.NotFound(
                        "AiFilterDecision", req.evaluationId()));

        // Set the operator label
        decision.setOperatorLabel(req.label());
        aiFilterDecisionRepository.save(decision);

        // Recalculate accuracy for the associated prompt version (if any)
        UUID promptVersionId = decision.getPromptVersionId();
        BigDecimal newAccuracy = null;
        if (promptVersionId != null) {
            var versionOpt = aiPromptVersionRepository.findById(promptVersionId);
            if (versionOpt.isPresent()) {
                var version = versionOpt.get();
                version.setTotalDecisions(version.getTotalDecisions() + 1);
                if ("CORRECT".equals(req.label())) {
                    version.setCorrectCount(version.getCorrectCount() + 1);
                }
                if (version.getTotalDecisions() > 0) {
                    newAccuracy = BigDecimal.valueOf(version.getCorrectCount())
                            .multiply(BigDecimal.valueOf(100))
                            .divide(BigDecimal.valueOf(version.getTotalDecisions()), 2, RoundingMode.HALF_UP);
                    version.setAccuracyScore(newAccuracy);
                }
                aiPromptVersionRepository.save(version);
            }
        }

        return new QueryResponse.AiDecisionLabelResult(true, promptVersionId, newAccuracy);
    }

    // ─── Time-Series Analytics (GraphQL Initiative 13) ────────────────────────

    /**
     * Returns time-bucketed request metrics using SQL {@code date_trunc}.
     * Granularity is validated and mapped to a PostgreSQL interval (minute, hour, day, week).
     */
    @RabbitListener(queues = RabbitTopology.QUEUE_AUDIT_TIME_SERIES)
    public QueryResponse.TimeSeriesResult handleTimeSeries(QueryRequest.TimeSeriesQuery req) {
        log.debug("RabbitMQ: received audit.time-series request: tenantId={} granularity={}",
                req.tenantId(), req.granularity());

        String pgGranularity = switch (req.granularity() != null ? req.granularity().toUpperCase() : "HOUR") {
            case "MINUTE" -> "minute";
            case "DAY"    -> "day";
            case "WEEK"   -> "week";
            default       -> "hour";
        };

        List<Object[]> rows = requestLogRepository.getTimeSeriesMetrics(
                req.tenantId(), req.routeId(),
                req.from(), req.to(), pgGranularity);

        var buckets = rows.stream().map(row -> {
            String timestamp = row[0] != null ? row[0].toString() : "";
            UUID   routeId   = row[1] != null ? UUID.fromString(row[1].toString()) : null;
            String routeName = row[2] != null ? row[2].toString() : "unknown";
            long   total     = row[3] != null ? ((Number) row[3]).longValue() : 0L;
            long   errors    = row[4] != null ? ((Number) row[4]).longValue() : 0L;
            double avgLat    = row[5] != null ? ((Number) row[5]).doubleValue() : 0.0;
            double p50       = row[6] != null ? ((Number) row[6]).doubleValue() : 0.0;
            double p95       = row[7] != null ? ((Number) row[7]).doubleValue() : 0.0;
            double p99       = row[8] != null ? ((Number) row[8]).doubleValue() : 0.0;
            double errorRate = total > 0 ? (double) errors / total : 0.0;

            return new QueryResponse.TimeSeriesResult.TimeSeriesBucket(
                    timestamp, routeId, routeName, total, errors, errorRate,
                    avgLat, p50, p95, p99, Map.of());
        }).toList();

        return new QueryResponse.TimeSeriesResult(buckets);
    }

    // ─── Prompt Version Mapping Helpers ────────────────────────────────────────

    private QueryResponse.PromptVersionsPage.PromptVersionSummary toVersionSummary(AiPromptVersion v) {
        return new QueryResponse.PromptVersionsPage.PromptVersionSummary(
                v.getId(), v.getFilterId(), v.getVersion(), v.getStatus(),
                v.getDescription(), v.getAccuracyScore(), v.getTotalDecisions(),
                v.getCreatedAt(), v.getActivatedAt());
    }

    private QueryResponse.PromptVersionDetail toVersionDetail(AiPromptVersion v) {
        return new QueryResponse.PromptVersionDetail(
                v.getId(), v.getFilterId(), v.getTenantId(), v.getVersion(),
                v.getPromptText(), v.getDescription(), v.getStatus(),
                v.getAccuracyScore(), v.getTotalDecisions(), v.getCorrectCount(),
                v.getCreatedBy(), v.getCreatedAt(), v.getActivatedAt(), v.getArchivedAt());
    }

    // ─── Alerting Engine (Initiative 15) ──────────────────────────────────────

    @RabbitListener(queues = RabbitTopology.QUEUE_ALERT_RULES_QUERY)
    public QueryResponse.AlertRulesPage handleAlertRulesQuery(QueryRequest.AlertRulesQuery req) {
        log.debug("RabbitMQ: received alert-rules.query: tenantId={}", req.tenantId());
        var pageable = PageRequest.of(req.page(), req.size(),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        var result = alertRuleRepository.findByTenantIdOrderByCreatedAtDesc(req.tenantId(), pageable);
        var content = result.getContent().stream().map(this::toAlertRuleSummary).toList();
        return new QueryResponse.AlertRulesPage(content, result.getTotalElements(),
                result.getTotalPages(), result.getNumber(), result.getSize());
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_ALERT_RULES_GET)
    public QueryResponse.AlertRuleDetail handleAlertRuleGet(QueryRequest.AlertRuleGet req) {
        log.debug("RabbitMQ: received alert-rules.get: id={}", req.id());
        var rule = alertRuleRepository.findByIdAndTenantId(req.id(), req.tenantId())
                .orElseThrow(() -> new io.routify.common.exception.RoutifyException.NotFound(
                        "AlertRule", req.id().toString()));
        return toAlertRuleDetail(rule);
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_ALERT_EVENTS_QUERY)
    public QueryResponse.AlertEventsPage handleAlertEventsQuery(QueryRequest.AlertEventsQuery req) {
        log.debug("RabbitMQ: received alert-events.query: ruleId={}", req.ruleId());
        var pageable = PageRequest.of(req.page(), req.size(),
                Sort.by(Sort.Direction.DESC, "occurredAt"));
        var result = alertEventRepository.findByRuleIdOrderByOccurredAtDesc(req.ruleId(), pageable);
        var content = result.getContent().stream().map(this::toAlertEventEntry).toList();
        return new QueryResponse.AlertEventsPage(content, result.getTotalElements(),
                result.getTotalPages(), result.getNumber(), result.getSize());
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_ALERT_RULES_COMMAND)
    @org.springframework.transaction.annotation.Transactional
    public QueryResponse.AlertRuleDetail handleAlertRuleCommand(QueryRequest.AlertRuleCommand req) {
        log.debug("RabbitMQ: received alert-rules.command: action={} ruleId={}", req.action(), req.ruleId());
        return switch (req.action()) {
            case "CREATE" -> createAlertRule(req);
            case "UPDATE" -> updateAlertRule(req);
            case "DELETE" -> deleteAlertRule(req);
            case "MUTE"   -> muteAlertRule(req);
            case "UNMUTE" -> unmuteAlertRule(req);
            default -> throw new io.routify.common.exception.RoutifyException.BadRequest(
                    "Unknown alert rule action: " + req.action());
        };
    }

    private QueryResponse.AlertRuleDetail createAlertRule(QueryRequest.AlertRuleCommand req) {
        var rule = AlertRule.builder()
                .tenantId(req.tenantId())
                .name(req.name())
                .description(req.description())
                .metric(req.metric())
                .routeId(req.routeId())
                .operator(req.operator())
                .threshold(req.threshold())
                .windowMinutes(req.windowMinutes() != null ? req.windowMinutes() : 5)
                .cooldownMinutes(req.cooldownMinutes() != null ? req.cooldownMinutes() : 30)
                .severity(req.severity() != null ? req.severity() : "WARNING")
                .enabled(req.enabled() != null ? req.enabled() : true)
                .createdBy(req.requestedBy())
                .build();
        return toAlertRuleDetail(alertRuleRepository.save(rule));
    }

    private QueryResponse.AlertRuleDetail updateAlertRule(QueryRequest.AlertRuleCommand req) {
        var rule = alertRuleRepository.findByIdAndTenantId(req.ruleId(), req.tenantId())
                .orElseThrow(() -> new io.routify.common.exception.RoutifyException.NotFound(
                        "AlertRule", req.ruleId().toString()));
        if (req.name() != null) rule.setName(req.name());
        if (req.description() != null) rule.setDescription(req.description());
        if (req.metric() != null) rule.setMetric(req.metric());
        if (req.routeId() != null) rule.setRouteId(req.routeId());
        if (req.operator() != null) rule.setOperator(req.operator());
        if (req.threshold() != null) rule.setThreshold(req.threshold());
        if (req.windowMinutes() != null) rule.setWindowMinutes(req.windowMinutes());
        if (req.cooldownMinutes() != null) rule.setCooldownMinutes(req.cooldownMinutes());
        if (req.severity() != null) rule.setSeverity(req.severity());
        if (req.enabled() != null) rule.setEnabled(req.enabled());
        return toAlertRuleDetail(alertRuleRepository.save(rule));
    }

    private QueryResponse.AlertRuleDetail deleteAlertRule(QueryRequest.AlertRuleCommand req) {
        var rule = alertRuleRepository.findByIdAndTenantId(req.ruleId(), req.tenantId())
                .orElseThrow(() -> new io.routify.common.exception.RoutifyException.NotFound(
                        "AlertRule", req.ruleId().toString()));
        var detail = toAlertRuleDetail(rule);
        alertRuleRepository.delete(rule);
        return detail;
    }

    private QueryResponse.AlertRuleDetail muteAlertRule(QueryRequest.AlertRuleCommand req) {
        var rule = alertRuleRepository.findByIdAndTenantId(req.ruleId(), req.tenantId())
                .orElseThrow(() -> new io.routify.common.exception.RoutifyException.NotFound(
                        "AlertRule", req.ruleId().toString()));
        int minutes = req.muteDurationMinutes() != null ? req.muteDurationMinutes() : 60;
        rule.setMutedUntil(Instant.now().plusSeconds((long) minutes * 60));
        return toAlertRuleDetail(alertRuleRepository.save(rule));
    }

    private QueryResponse.AlertRuleDetail unmuteAlertRule(QueryRequest.AlertRuleCommand req) {
        var rule = alertRuleRepository.findByIdAndTenantId(req.ruleId(), req.tenantId())
                .orElseThrow(() -> new io.routify.common.exception.RoutifyException.NotFound(
                        "AlertRule", req.ruleId().toString()));
        rule.setMutedUntil(null);
        return toAlertRuleDetail(alertRuleRepository.save(rule));
    }

    private QueryResponse.AlertRulesPage.AlertRuleSummary toAlertRuleSummary(AlertRule r) {
        return new QueryResponse.AlertRulesPage.AlertRuleSummary(
                r.getId(), r.getTenantId(), r.getName(), r.getDescription(),
                r.getMetric(), r.getRouteId(), r.getOperator(), r.getThreshold(),
                r.getWindowMinutes(), r.getCooldownMinutes(), r.getSeverity(),
                r.isEnabled(), r.getCurrentState(), r.getStateChangedAt(),
                r.getConsecutiveBreaches(), r.getLastEvaluatedAt(), r.getLastFiredAt(),
                r.getMutedUntil(), r.getCreatedAt());
    }

    private QueryResponse.AlertRuleDetail toAlertRuleDetail(AlertRule r) {
        return new QueryResponse.AlertRuleDetail(
                r.getId(), r.getTenantId(), r.getName(), r.getDescription(),
                r.getMetric(), r.getRouteId(), r.getOperator(), r.getThreshold(),
                r.getWindowMinutes(), r.getCooldownMinutes(), r.getSeverity(),
                r.isEnabled(), r.getCurrentState(), r.getStateChangedAt(),
                r.getConsecutiveBreaches(), r.getLastEvaluatedAt(), r.getLastFiredAt(),
                r.getMutedUntil(), r.getCreatedBy(), r.getCreatedAt(), r.getUpdatedAt());
    }

    private QueryResponse.AlertEventsPage.AlertEventEntry toAlertEventEntry(AlertEvent e) {
        return new QueryResponse.AlertEventsPage.AlertEventEntry(
                e.getId(), e.getRuleId(), e.getTenantId(), e.getTransition(),
                e.getMetricValue(), e.getThreshold(), e.getMessage(), e.getOccurredAt());
    }
}
