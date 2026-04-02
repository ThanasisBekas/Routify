package gr.routify.audit.messaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.audit.domain.AuditLogEntry;
import gr.routify.audit.domain.RequestLog;
import gr.routify.audit.replay.FailedRequestReplayService;
import gr.routify.audit.repository.AuditLogRepository;
import gr.routify.audit.repository.RequestLogRepository;
import gr.routify.common.event.RabbitTopology;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RabbitMQ request/reply handler for routify-audit-service.
 *
 * <p>Responds to audit log, request log, and replay queries/commands from routify-admin-api.
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

    // ─── Audit Event Queries ──────────────────────────────────────────────────

    @RabbitListener(queues = RabbitTopology.QUEUE_AUDIT_EVENTS_QUERY)
    public String handleAuditEventsQuery(String requestBody) {
        log.debug("RabbitMQ: received audit.events.query request");
        try {
            Map<String, Object> req = objectMapper.readValue(requestBody, new TypeReference<>() {});
            UUID   tenantId      = parseUuid(req.get("tenantId"));
            String eventType     = str(req.get("eventType"));
            String aggregateType = str(req.get("aggregateType"));
            String aggregateId   = str(req.get("aggregateId"));
            String fromStr       = str(req.get("from"));
            String toStr         = str(req.get("to"));
            int    page          = parseInt(req.get("page"), 0);
            int    size          = parseInt(req.get("size"), 50);

            var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "occurredAt"));

            Page<AuditLogEntry> result;
            if (fromStr != null && toStr != null) {
                result = auditLogRepository.findByTenantAndTimeRange(
                        tenantId, Instant.parse(fromStr), Instant.parse(toStr), pageable);
            } else if (eventType != null) {
                result = auditLogRepository.findByTenantIdAndEventTypeOrderByOccurredAtDesc(
                        tenantId, eventType, pageable);
            } else if (aggregateType != null && aggregateId != null) {
                result = auditLogRepository.findByTenantIdAndAggregateTypeAndAggregateIdOrderByOccurredAtDesc(
                        tenantId, aggregateType, aggregateId, pageable);
            } else {
                result = auditLogRepository.findByTenantIdOrderByOccurredAtDesc(tenantId, pageable);
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
            Map<String, Object> req = objectMapper.readValue(requestBody, new TypeReference<>() {});
            UUID   tenantId  = parseUuid(req.get("tenantId"));
            UUID   routeId   = parseUuid(req.get("routeId"));
            String fromStr   = str(req.get("from"));
            String toStr     = str(req.get("to"));
            int    page      = parseInt(req.get("page"), 0);
            int    size      = parseInt(req.get("size"), 50);

            var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "requestedAt"));

            Page<RequestLog> result;
            if (routeId != null) {
                result = requestLogRepository.findByTenantIdAndRouteIdOrderByRequestedAtDesc(tenantId, routeId, pageable);
            } else if (fromStr != null && toStr != null) {
                result = requestLogRepository.findByTenantAndTimeRange(
                        tenantId, Instant.parse(fromStr), Instant.parse(toStr), pageable);
            } else {
                result = requestLogRepository.findByTenantIdOrderByRequestedAtDesc(tenantId, pageable);
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
            Map<String, Object> req = objectMapper.readValue(requestBody, new TypeReference<>() {});
            UUID tenantId = parseUuid(req.get("tenantId"));
            UUID routeId  = parseUuid(req.get("routeId"));

            Instant since = Instant.now().minusSeconds(86400);
            Object[] stats = requestLogRepository.getRouteStats(tenantId, routeId, since);

            Map<String, Object> result = new HashMap<>();
            result.put("routeId", routeId != null ? routeId.toString() : null);
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
            Map<String, Object> req = objectMapper.readValue(requestBody, new TypeReference<>() {});
            UUID tenantId = parseUuid(req.get("tenantId"));
            UUID routeId  = parseUuid(req.get("routeId"));
            int  page     = parseInt(req.get("page"), 0);
            int  size     = parseInt(req.get("size"), 50);

            var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "requestedAt"));
            Page<RequestLog> result = routeId != null
                    ? requestLogRepository.findFailedByTenantIdAndRouteId(tenantId, routeId, pageable)
                    : requestLogRepository.findFailedByTenantId(tenantId, pageable);

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
            Map<String, Object> req = objectMapper.readValue(requestBody, new TypeReference<>() {});
            UUID tenantId = parseUuid(req.get("tenantId"));
            UUID routeId  = parseUuid(req.get("routeId"));
            int  page     = parseInt(req.get("page"), 0);
            int  size     = parseInt(req.get("size"), 50);

            var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "requestedAt"));
            Page<RequestLog> result = routeId != null
                    ? requestLogRepository.findPendingReplayByTenantIdAndRouteId(tenantId, routeId, pageable)
                    : requestLogRepository.findPendingReplayByTenantId(tenantId, pageable);

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
            Map<String, Object> req = objectMapper.readValue(requestBody, new TypeReference<>() {});
            UUID tenantId = parseUuid(req.get("tenantId"));

            List<Object[]> rows = requestLogRepository.countFailedByReplayStatus(tenantId);

            // Initialise all expected keys to 0 so the frontend always receives a complete object
            Map<String, Object> result = new HashMap<>();
            result.put("pending",    0L);
            result.put("inProgress", 0L);
            result.put("succeeded",  0L);
            result.put("failed",     0L);
            result.put("skipped",    0L);

            for (Object[] row : rows) {
                String dbKey = row[0] != null ? String.valueOf(row[0]) : null;
                long   count = ((Number) row[1]).longValue();
                // Map DB enum values to the camelCase keys expected by the dashboard
                String apiKey = switch (dbKey != null ? dbKey : "") {
                    case "PENDING"     -> "pending";
                    case "IN_PROGRESS" -> "inProgress";
                    case "SUCCEEDED"   -> "succeeded";
                    case "FAILED"      -> "failed";
                    case "SKIPPED"     -> "skipped";
                    default            -> null;
                };
                if (apiKey != null) {
                    result.put(apiKey, count);
                }
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
            Map<String, Object> req = objectMapper.readValue(requestBody, new TypeReference<>() {});
            UUID id       = parseUuid(req.get("id"));
            UUID tenantId = parseUuid(req.get("tenantId"));

            FailedRequestReplayService.ReplayResult result = replayService.replay(id, tenantId);

            Map<String, Object> response = new HashMap<>();
            response.put("requestLogId",   result.requestLogId() != null ? result.requestLogId().toString() : null);
            response.put("outcome",        result.outcome().name());
            response.put("responseStatus", result.responseStatus());
            response.put("message",        result.message());
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
            Map<String, Object> req = objectMapper.readValue(requestBody, new TypeReference<>() {});
            UUID tenantId = parseUuid(req.get("tenantId"));
            int  limit    = parseInt(req.get("limit"), 50);

            FailedRequestReplayService.BulkReplayResult result = replayService.replayAll(tenantId, limit);

            Map<String, Object> response = new HashMap<>();
            response.put("total",     result.total());
            response.put("succeeded", result.succeeded());
            response.put("failed",    result.failed());
            response.put("skipped",   result.skipped());
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("RabbitMQ: audit.replay.bulk failed: {}", e.getMessage(), e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

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

    private UUID parseUuid(Object val) {
        if (val == null || val.toString().isBlank()) return null;
        return UUID.fromString(val.toString());
    }

    private int parseInt(Object val, int def) {
        try { return val != null ? Integer.parseInt(val.toString()) : def; } catch (Exception e) { return def; }
    }

    private String str(Object val) {
        return (val != null && !val.toString().isBlank()) ? val.toString() : null;
    }
}

