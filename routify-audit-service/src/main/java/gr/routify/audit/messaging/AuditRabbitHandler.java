package gr.routify.audit.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.audit.domain.AuditLogEntry;
import gr.routify.audit.domain.RequestLog;
import gr.routify.audit.replay.FailedRequestReplayService;
import gr.routify.audit.repository.AuditLogRepository;
import gr.routify.audit.repository.RequestLogRepository;
import gr.routify.common.event.KafkaTopics;
import gr.routify.common.event.QueryRequest;
import gr.routify.common.event.RabbitTopology;
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
 * <p>All request bodies are deserialised into strongly-typed {@link QueryRequest} records.
 * Audit data is immutable — only replay commands mutate state.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditRabbitHandler {

    private final AuditLogRepository         auditLogRepository;
    private final RequestLogRepository       requestLogRepository;
    private final FailedRequestReplayService replayService;
    private final ObjectMapper               objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;

    // ─── Audit Event Queries ──────────────────────────────────────────────────

    @RabbitListener(queues = RabbitTopology.QUEUE_AUDIT_EVENTS_QUERY)
    public String handleAuditEventsQuery(String requestBody) {
        log.debug("RabbitMQ: received audit.events.query request");
        try {
            QueryRequest.AuditEventsQuery req = objectMapper.readValue(
                    requestBody, QueryRequest.AuditEventsQuery.class);

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

            Map<String, Object> response = new HashMap<>();
            response.put("content",       result.getContent().stream().map(this::auditToMap).toList());
            response.put("totalElements", result.getTotalElements());
            response.put("totalPages",    result.getTotalPages());
            response.put("page",          result.getNumber());
            response.put("size",          result.getSize());
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("RabbitMQ: audit.events.query failed: {}", e.getMessage(), e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ─── Request Log Queries ──────────────────────────────────────────────────

    @RabbitListener(queues = RabbitTopology.QUEUE_AUDIT_REQUESTS_QUERY)
    public String handleRequestsQuery(String requestBody) {
        log.debug("RabbitMQ: received audit.requests.query request");
        try {
            QueryRequest.AuditRequestsQuery req = objectMapper.readValue(
                    requestBody, QueryRequest.AuditRequestsQuery.class);

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

            Map<String, Object> response = new HashMap<>();
            response.put("content",       result.getContent().stream().map(this::requestLogToMap).toList());
            response.put("totalElements", result.getTotalElements());
            response.put("totalPages",    result.getTotalPages());
            response.put("page",          result.getNumber());
            response.put("size",          result.getSize());
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("RabbitMQ: audit.requests.query failed: {}", e.getMessage(), e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_AUDIT_REQUESTS_STATS)
    public String handleRequestStats(String requestBody) {
        log.debug("RabbitMQ: received audit.requests.stats request");
        try {
            QueryRequest.AuditRequestStats req = objectMapper.readValue(
                    requestBody, QueryRequest.AuditRequestStats.class);

            Instant since = Instant.now().minusSeconds(86400);
            Object[] stats = requestLogRepository.getRouteStats(req.tenantId(), req.routeId(), since);

            Map<String, Object> result = new HashMap<>();
            result.put("routeId", req.routeId() != null ? req.routeId().toString() : null);
            if (stats != null && stats[0] != null) {
                result.put("totalRequests",  ((Number) stats[0]).longValue());
                result.put("avgDurationMs",  stats[1] != null ? ((Number) stats[1]).doubleValue() : 0.0);
                result.put("maxDurationMs",  stats[2] != null ? ((Number) stats[2]).longValue() : 0L);
                result.put("errorCount",     stats[3] != null ? ((Number) stats[3]).longValue() : 0L);
            } else {
                result.put("totalRequests", 0L);
                result.put("avgDurationMs", 0.0);
                result.put("maxDurationMs", 0L);
                result.put("errorCount",    0L);
            }
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("RabbitMQ: audit.requests.stats failed: {}", e.getMessage(), e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ─── Replay Queries ───────────────────────────────────────────────────────

    @RabbitListener(queues = RabbitTopology.QUEUE_AUDIT_REPLAY_FAILED_QUERY)
    public String handleReplayFailedQuery(String requestBody) {
        log.debug("RabbitMQ: received audit.replay.failed.query request");
        try {
            QueryRequest.ReplayFailedQuery req = objectMapper.readValue(
                    requestBody, QueryRequest.ReplayFailedQuery.class);

            var pageable = PageRequest.of(req.page(), req.size(),
                    Sort.by(Sort.Direction.DESC, "requestedAt"));
            Page<RequestLog> result = req.routeId() != null
                    ? requestLogRepository.findFailedByTenantIdAndRouteId(
                            req.tenantId(), req.routeId(), pageable)
                    : requestLogRepository.findFailedByTenantId(req.tenantId(), pageable);

            Map<String, Object> response = new HashMap<>();
            response.put("content",       result.getContent().stream().map(this::requestLogToMap).toList());
            response.put("totalElements", result.getTotalElements());
            response.put("totalPages",    result.getTotalPages());
            response.put("page",          result.getNumber());
            response.put("size",          result.getSize());
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("RabbitMQ: audit.replay.failed.query failed: {}", e.getMessage(), e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_AUDIT_REPLAY_PENDING_QUERY)
    public String handleReplayPendingQuery(String requestBody) {
        log.debug("RabbitMQ: received audit.replay.pending.query request");
        try {
            QueryRequest.ReplayPendingQuery req = objectMapper.readValue(
                    requestBody, QueryRequest.ReplayPendingQuery.class);

            var pageable = PageRequest.of(req.page(), req.size(),
                    Sort.by(Sort.Direction.DESC, "requestedAt"));
            Page<RequestLog> result = req.routeId() != null
                    ? requestLogRepository.findPendingReplayByTenantIdAndRouteId(
                            req.tenantId(), req.routeId(), pageable)
                    : requestLogRepository.findPendingReplayByTenantId(req.tenantId(), pageable);

            Map<String, Object> response = new HashMap<>();
            response.put("content",       result.getContent().stream().map(this::requestLogToMap).toList());
            response.put("totalElements", result.getTotalElements());
            response.put("totalPages",    result.getTotalPages());
            response.put("page",          result.getNumber());
            response.put("size",          result.getSize());
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("RabbitMQ: audit.replay.pending.query failed: {}", e.getMessage(), e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_AUDIT_REPLAY_STATS)
    public String handleReplayStats(String requestBody) {
        log.debug("RabbitMQ: received audit.replay.stats request");
        try {
            QueryRequest.ReplayStats req = objectMapper.readValue(
                    requestBody, QueryRequest.ReplayStats.class);

            List<Object[]> rows = requestLogRepository.countFailedByReplayStatus(req.tenantId());

            Map<String, Object> result = new HashMap<>();
            result.put("pending",    0L);
            result.put("inProgress", 0L);
            result.put("succeeded",  0L);
            result.put("failed",     0L);
            result.put("skipped",    0L);

            for (Object[] row : rows) {
                String dbKey = row[0] != null ? String.valueOf(row[0]) : null;
                long   count = ((Number) row[1]).longValue();
                String apiKey = switch (dbKey != null ? dbKey : "") {
                    case "PENDING"     -> "pending";
                    case "IN_PROGRESS" -> "inProgress";
                    case "SUCCEEDED"   -> "succeeded";
                    case "FAILED"      -> "failed";
                    case "SKIPPED"     -> "skipped";
                    default            -> null;
                };
                if (apiKey != null) result.put(apiKey, count);
            }
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("RabbitMQ: audit.replay.stats failed: {}", e.getMessage(), e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ─── Replay Commands ──────────────────────────────────────────────────────

    @RabbitListener(queues = RabbitTopology.QUEUE_AUDIT_REPLAY_SINGLE)
    public String handleReplaySingle(String requestBody) {
        log.debug("RabbitMQ: received audit.replay.single command");
        try {
            QueryRequest.ReplaySingle req = objectMapper.readValue(
                    requestBody, QueryRequest.ReplaySingle.class);

            FailedRequestReplayService.ReplayResult result = replayService.replay(req.id(), req.tenantId());

            Map<String, Object> response = new HashMap<>();
            response.put("requestLogId",   result.requestLogId() != null ? result.requestLogId().toString() : null);
            response.put("outcome",        result.outcome().name());
            response.put("responseStatus", result.responseStatus());
            response.put("message",        result.message());

            publishReplayEvent("REPLAY_COMPLETED", req.tenantId(), response);
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("RabbitMQ: audit.replay.single failed: {}", e.getMessage(), e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_AUDIT_REPLAY_BULK)
    public String handleReplayBulk(String requestBody) {
        log.debug("RabbitMQ: received audit.replay.bulk command");
        try {
            QueryRequest.ReplayBulk req = objectMapper.readValue(
                    requestBody, QueryRequest.ReplayBulk.class);

            FailedRequestReplayService.BulkReplayResult result =
                    replayService.replayAll(req.tenantId(), req.limit());

            Map<String, Object> response = new HashMap<>();
            response.put("total",     result.total());
            response.put("succeeded", result.succeeded());
            response.put("failed",    result.failed());
            response.put("skipped",   result.skipped());

            publishReplayEvent("REPLAY_BULK_COMPLETED", req.tenantId(), response);
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("RabbitMQ: audit.replay.bulk failed: {}", e.getMessage(), e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Publishes a replay event to Kafka's AUDIT_EVENTS topic so that
     * routify-admin-api's WebSocket broadcaster can push it to the dashboard.
     */
    private void publishReplayEvent(String eventType, UUID tenantId, Map<String, Object> data) {
        try {
            Map<String, Object> event = new HashMap<>(data);
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

    private Map<String, Object> auditToMap(AuditLogEntry e) {
        Map<String, Object> m = new HashMap<>();
        m.put("eventId",       e.getId());
        m.put("tenantId",      e.getTenantId());
        m.put("eventType",     e.getEventType());
        m.put("aggregateType", e.getAggregateType());
        m.put("aggregateId",   e.getAggregateId());
        m.put("actorId",       e.getActorId());
        m.put("correlationId", e.getCorrelationId());
        m.put("occurredAt",    e.getOccurredAt() != null ? e.getOccurredAt().toString() : null);
        m.put("recordedAt",    e.getRecordedAt() != null ? e.getRecordedAt().toString() : null);
        return m;
    }

    private Map<String, Object> requestLogToMap(RequestLog r) {
        Map<String, Object> m = new HashMap<>();
        m.put("id",                   r.getId());
        m.put("tenantId",             r.getTenantId());
        m.put("routeId",              r.getRouteId());
        m.put("routeName",            r.getRouteName());
        m.put("correlationId",        r.getCorrelationId());
        m.put("method",               r.getHttpMethod());
        m.put("path",                 r.getPath());
        m.put("queryString",          r.getQueryString());
        m.put("upstreamUri",          r.getUpstreamUri());
        m.put("responseStatus",       r.getResponseStatus());
        m.put("durationMs",           r.getDurationMs());
        m.put("clientIp",             r.getClientIp());
        m.put("userId",               r.getUserId());
        m.put("errorMessage",         r.getErrorMessage());
        m.put("failed",               r.isFailed());
        m.put("requestHeaders",       r.getRequestHeaders());
        m.put("responseHeaders",      r.getResponseHeaders());
        m.put("requestBody",          r.getRequestBody());
        m.put("responseBody",         r.getResponseBody());
        m.put("replayStatus",         r.getReplayStatus());
        m.put("replayCount",          r.getReplayCount());
        m.put("replayedAt",           r.getReplayedAt() != null ? r.getReplayedAt().toString() : null);
        m.put("replayResponseStatus", r.getReplayResponseStatus());
        m.put("replayError",          r.getReplayError());
        m.put("requestedAt",          r.getRequestedAt() != null ? r.getRequestedAt().toString() : null);
        return m;
    }
}
