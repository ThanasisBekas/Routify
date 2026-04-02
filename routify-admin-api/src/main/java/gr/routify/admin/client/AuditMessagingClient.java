package gr.routify.admin.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.common.client.AmqpServiceClientSupport;
import gr.routify.common.event.QueryRequest;
import gr.routify.common.event.RabbitTopology;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Admin-API messaging client for audit log and request log queries in routify-audit-service.
 *
 * <p>All audit queries use RabbitMQ request/reply via strongly-typed {@link QueryRequest} records.
 * Audit data is immutable — there are no command operations from the dashboard.
 */
@Slf4j
@Component
public class AuditMessagingClient extends AmqpServiceClientSupport {

    public AuditMessagingClient(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        super(rabbitTemplate, objectMapper, RabbitTopology.EXCHANGE_AUDIT_SERVICE, "admin-api");
    }

    // ─── Audit Event Queries ──────────────────────────────────────────────────

    @CircuitBreaker(name = "audit-service", fallbackMethod = "queryAuditEventsFallback")
    public Map<String, Object> queryAuditEvents(UUID tenantId, String eventType,
                                                String aggregateType, String aggregateId,
                                                String from, String to, int page, int size) {
        try {
            return rpc(RabbitTopology.RK_AUDIT_EVENTS_QUERY,
                    new QueryRequest.AuditEventsQuery(tenantId, eventType, aggregateType,
                            aggregateId, from, to, page, size));
        } catch (Exception e) {
            log.error("queryAuditEvents failed: {}", e.getMessage(), e);
            return Map.of("error", e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private Map<String, Object> queryAuditEventsFallback(UUID tenantId, String eventType,
                                                          String aggregateType, String aggregateId,
                                                          String from, String to, int page, int size,
                                                          Throwable t) {
        log.warn("queryAuditEvents circuit open or timed out: {}", t.getMessage());
        return Map.of("error", "audit-service temporarily unavailable", "circuitOpen", true);
    }

    // ─── Request Log Queries ──────────────────────────────────────────────────

    @CircuitBreaker(name = "audit-service", fallbackMethod = "queryRequestLogsFallback")
    public Map<String, Object> queryRequestLogs(UUID tenantId, UUID routeId,
                                                String from, String to, int page, int size) {
        try {
            return rpc(RabbitTopology.RK_AUDIT_REQUESTS_QUERY,
                    new QueryRequest.AuditRequestsQuery(tenantId, routeId, from, to, page, size));
        } catch (Exception e) {
            log.error("queryRequestLogs failed: {}", e.getMessage(), e);
            return Map.of("error", e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private Map<String, Object> queryRequestLogsFallback(UUID tenantId, UUID routeId,
                                                          String from, String to, int page, int size,
                                                          Throwable t) {
        log.warn("queryRequestLogs circuit open or timed out: {}", t.getMessage());
        return Map.of("error", "audit-service temporarily unavailable", "circuitOpen", true);
    }

    @CircuitBreaker(name = "audit-service", fallbackMethod = "getRequestStatsFallback")
    public Map<String, Object> getRequestStats(UUID tenantId, UUID routeId) {
        try {
            return rpc(RabbitTopology.RK_AUDIT_REQUESTS_STATS,
                    new QueryRequest.AuditRequestStats(tenantId, routeId));
        } catch (Exception e) {
            log.error("getRequestStats failed: {}", e.getMessage(), e);
            return Map.of("error", e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private Map<String, Object> getRequestStatsFallback(UUID tenantId, UUID routeId, Throwable t) {
        log.warn("getRequestStats circuit open or timed out: {}", t.getMessage());
        return Map.of("error", "audit-service temporarily unavailable", "circuitOpen", true);
    }

    // ─── Replay Queries ───────────────────────────────────────────────────────

    @CircuitBreaker(name = "audit-service", fallbackMethod = "queryReplayFailedFallback")
    public Map<String, Object> queryReplayFailed(UUID tenantId, UUID routeId, int page, int size) {
        try {
            return rpc(RabbitTopology.RK_AUDIT_REPLAY_FAILED_QUERY,
                    new QueryRequest.ReplayFailedQuery(tenantId, routeId, page, size));
        } catch (Exception e) {
            log.error("queryReplayFailed failed: {}", e.getMessage(), e);
            return Map.of("error", e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private Map<String, Object> queryReplayFailedFallback(UUID tenantId, UUID routeId,
                                                           int page, int size, Throwable t) {
        log.warn("queryReplayFailed circuit open or timed out: {}", t.getMessage());
        return Map.of("error", "audit-service temporarily unavailable", "circuitOpen", true);
    }

    @CircuitBreaker(name = "audit-service", fallbackMethod = "queryReplayPendingFallback")
    public Map<String, Object> queryReplayPending(UUID tenantId, UUID routeId, int page, int size) {
        try {
            return rpc(RabbitTopology.RK_AUDIT_REPLAY_PENDING_QUERY,
                    new QueryRequest.ReplayPendingQuery(tenantId, routeId, page, size));
        } catch (Exception e) {
            log.error("queryReplayPending failed: {}", e.getMessage(), e);
            return Map.of("error", e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private Map<String, Object> queryReplayPendingFallback(UUID tenantId, UUID routeId,
                                                            int page, int size, Throwable t) {
        log.warn("queryReplayPending circuit open or timed out: {}", t.getMessage());
        return Map.of("error", "audit-service temporarily unavailable", "circuitOpen", true);
    }

    @CircuitBreaker(name = "audit-service", fallbackMethod = "getReplayStatsFallback")
    public Map<String, Object> getReplayStats(UUID tenantId) {
        try {
            return rpc(RabbitTopology.RK_AUDIT_REPLAY_STATS, new QueryRequest.ReplayStats(tenantId));
        } catch (Exception e) {
            log.error("getReplayStats failed: {}", e.getMessage(), e);
            return Map.of("error", e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private Map<String, Object> getReplayStatsFallback(UUID tenantId, Throwable t) {
        log.warn("getReplayStats circuit open or timed out: {}", t.getMessage());
        return Map.of("error", "audit-service temporarily unavailable", "circuitOpen", true);
    }

    // ─── Replay Commands ──────────────────────────────────────────────────────

    @CircuitBreaker(name = "audit-service", fallbackMethod = "replaySingleFallback")
    public Map<String, Object> replaySingle(UUID id, UUID tenantId) {
        try {
            return rpc(RabbitTopology.RK_AUDIT_REPLAY_SINGLE, new QueryRequest.ReplaySingle(id, tenantId));
        } catch (Exception e) {
            log.error("replaySingle failed: {}", e.getMessage(), e);
            return Map.of("error", e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private Map<String, Object> replaySingleFallback(UUID id, UUID tenantId, Throwable t) {
        log.warn("replaySingle circuit open or timed out: {}", t.getMessage());
        return Map.of("error", "audit-service temporarily unavailable", "circuitOpen", true);
    }

    @CircuitBreaker(name = "audit-service", fallbackMethod = "replayBulkFallback")
    public Map<String, Object> replayBulk(UUID tenantId, int limit) {
        try {
            return rpc(RabbitTopology.RK_AUDIT_REPLAY_BULK, new QueryRequest.ReplayBulk(tenantId, limit));
        } catch (Exception e) {
            log.error("replayBulk failed: {}", e.getMessage(), e);
            return Map.of("error", e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private Map<String, Object> replayBulkFallback(UUID tenantId, int limit, Throwable t) {
        log.warn("replayBulk circuit open or timed out: {}", t.getMessage());
        return Map.of("error", "audit-service temporarily unavailable", "circuitOpen", true);
    }
}
