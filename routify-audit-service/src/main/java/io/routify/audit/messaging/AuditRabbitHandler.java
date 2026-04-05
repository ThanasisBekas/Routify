package io.routify.audit.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.routify.audit.domain.AiFilterDecision;
import io.routify.audit.domain.AuditLogEntry;
import io.routify.audit.domain.RequestLog;
import io.routify.audit.replay.FailedRequestReplayService;
import io.routify.audit.repository.AiFilterDecisionRepository;
import io.routify.audit.repository.AuditLogRepository;
import io.routify.audit.repository.RequestLogRepository;
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
}
