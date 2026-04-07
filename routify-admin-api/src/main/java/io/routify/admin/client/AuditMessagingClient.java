package io.routify.admin.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.routify.common.client.AmqpServiceClientSupport;
import io.routify.common.event.QueryRequest;
import io.routify.common.event.QueryResponse;
import io.routify.common.event.RabbitTopology;
import io.routify.common.observability.RoutifyMetrics;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
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

    public AuditMessagingClient(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper, RoutifyMetrics metrics) {
        super(rabbitTemplate, objectMapper, RabbitTopology.EXCHANGE_AUDIT_SERVICE, "admin-api", metrics);
    }

    // ─── Audit Event Queries ──────────────────────────────────────────────────

    @CircuitBreaker(name = "audit-service", fallbackMethod = "queryAuditEventsFallback")
    public QueryResponse.AuditEventsPage queryAuditEvents(UUID tenantId, String eventType,
                                                          String aggregateType, String aggregateId,
                                                          String from, String to, int page, int size) {
        try {
            return rpc(RabbitTopology.RK_AUDIT_EVENTS_QUERY,
                    new QueryRequest.AuditEventsQuery(tenantId, eventType, aggregateType,
                            aggregateId, from, to, page, size),
                    QueryResponse.AuditEventsPage.class);
        } catch (Exception e) {
            log.error("queryAuditEvents failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    @SuppressWarnings("unused")
    private QueryResponse.AuditEventsPage queryAuditEventsFallback(UUID tenantId, String eventType,
                                                                    String aggregateType, String aggregateId,
                                                                    String from, String to, int page, int size,
                                                                    Throwable t) {
        log.warn("queryAuditEvents circuit open or timed out: {}", t.getMessage());
        return new QueryResponse.AuditEventsPage(List.of(), 0L, 0, page, size);
    }

    // ─── Request Log Queries ──────────────────────────────────────────────────

    @CircuitBreaker(name = "audit-service", fallbackMethod = "queryRequestLogsFallback")
    public QueryResponse.RequestLogsPage queryRequestLogs(UUID tenantId, UUID routeId,
                                                          String from, String to, int page, int size) {
        try {
            return rpc(RabbitTopology.RK_AUDIT_REQUESTS_QUERY,
                    new QueryRequest.AuditRequestsQuery(tenantId, routeId, from, to, page, size),
                    QueryResponse.RequestLogsPage.class);
        } catch (Exception e) {
            log.error("queryRequestLogs failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    @SuppressWarnings("unused")
    private QueryResponse.RequestLogsPage queryRequestLogsFallback(UUID tenantId, UUID routeId,
                                                                    String from, String to, int page, int size,
                                                                    Throwable t) {
        log.warn("queryRequestLogs circuit open or timed out: {}", t.getMessage());
        return new QueryResponse.RequestLogsPage(List.of(), 0L, 0, page, size);
    }

    @CircuitBreaker(name = "audit-service", fallbackMethod = "getRequestStatsFallback")
    public QueryResponse.RequestStatsResult getRequestStats(UUID tenantId, UUID routeId) {
        try {
            return rpc(RabbitTopology.RK_AUDIT_REQUESTS_STATS,
                    new QueryRequest.AuditRequestStats(tenantId, routeId),
                    QueryResponse.RequestStatsResult.class);
        } catch (Exception e) {
            log.error("getRequestStats failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    @SuppressWarnings("unused")
    private QueryResponse.RequestStatsResult getRequestStatsFallback(UUID tenantId, UUID routeId, Throwable t) {
        log.warn("getRequestStats circuit open or timed out: {}", t.getMessage());
        return new QueryResponse.RequestStatsResult(routeId, 0L, 0.0, 0L, 0L);
    }

    // ─── Replay Queries ───────────────────────────────────────────────────────

    @CircuitBreaker(name = "audit-service", fallbackMethod = "queryReplayFailedFallback")
    public QueryResponse.RequestLogsPage queryReplayFailed(UUID tenantId, UUID routeId, int page, int size) {
        try {
            return rpc(RabbitTopology.RK_AUDIT_REPLAY_FAILED_QUERY,
                    new QueryRequest.ReplayFailedQuery(tenantId, routeId, page, size),
                    QueryResponse.RequestLogsPage.class);
        } catch (Exception e) {
            log.error("queryReplayFailed failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    @SuppressWarnings("unused")
    private QueryResponse.RequestLogsPage queryReplayFailedFallback(UUID tenantId, UUID routeId,
                                                                     int page, int size, Throwable t) {
        log.warn("queryReplayFailed circuit open or timed out: {}", t.getMessage());
        return new QueryResponse.RequestLogsPage(List.of(), 0L, 0, page, size);
    }

    @CircuitBreaker(name = "audit-service", fallbackMethod = "queryReplayPendingFallback")
    public QueryResponse.RequestLogsPage queryReplayPending(UUID tenantId, UUID routeId, int page, int size) {
        try {
            return rpc(RabbitTopology.RK_AUDIT_REPLAY_PENDING_QUERY,
                    new QueryRequest.ReplayPendingQuery(tenantId, routeId, page, size),
                    QueryResponse.RequestLogsPage.class);
        } catch (Exception e) {
            log.error("queryReplayPending failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    @SuppressWarnings("unused")
    private QueryResponse.RequestLogsPage queryReplayPendingFallback(UUID tenantId, UUID routeId,
                                                                      int page, int size, Throwable t) {
        log.warn("queryReplayPending circuit open or timed out: {}", t.getMessage());
        return new QueryResponse.RequestLogsPage(List.of(), 0L, 0, page, size);
    }

    @CircuitBreaker(name = "audit-service", fallbackMethod = "getReplayStatsFallback")
    public QueryResponse.ReplayStatsResult getReplayStats(UUID tenantId) {
        try {
            return rpc(RabbitTopology.RK_AUDIT_REPLAY_STATS,
                    new QueryRequest.ReplayStats(tenantId),
                    QueryResponse.ReplayStatsResult.class);
        } catch (Exception e) {
            log.error("getReplayStats failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    @SuppressWarnings("unused")
    private QueryResponse.ReplayStatsResult getReplayStatsFallback(UUID tenantId, Throwable t) {
        log.warn("getReplayStats circuit open or timed out: {}", t.getMessage());
        return new QueryResponse.ReplayStatsResult(0L, 0L, 0L, 0L, 0L);
    }

    // ─── Replay Commands ──────────────────────────────────────────────────────

    @CircuitBreaker(name = "audit-service", fallbackMethod = "replaySingleFallback")
    public QueryResponse.ReplaySingleResult replaySingle(UUID id, UUID tenantId) {
        try {
            return rpc(RabbitTopology.RK_AUDIT_REPLAY_SINGLE,
                    new QueryRequest.ReplaySingle(id, tenantId),
                    QueryResponse.ReplaySingleResult.class);
        } catch (Exception e) {
            log.error("replaySingle failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    @SuppressWarnings("unused")
    private QueryResponse.ReplaySingleResult replaySingleFallback(UUID id, UUID tenantId, Throwable t) {
        log.warn("replaySingle circuit open or timed out: {}", t.getMessage());
        return new QueryResponse.ReplaySingleResult(id, "SKIPPED", null, "audit-service temporarily unavailable");
    }

    @CircuitBreaker(name = "audit-service", fallbackMethod = "replayBulkFallback")
    public QueryResponse.ReplayBulkResult replayBulk(UUID tenantId, int limit) {
        try {
            return rpc(RabbitTopology.RK_AUDIT_REPLAY_BULK,
                    new QueryRequest.ReplayBulk(tenantId, limit),
                    QueryResponse.ReplayBulkResult.class);
        } catch (Exception e) {
            log.error("replayBulk failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    @SuppressWarnings("unused")
    private QueryResponse.ReplayBulkResult replayBulkFallback(UUID tenantId, int limit, Throwable t) {
        log.warn("replayBulk circuit open or timed out: {}", t.getMessage());
        return new QueryResponse.ReplayBulkResult(0, 0, 0, 0);
    }

    // ─── Route Health (Gateway Health Dashboard v2) ────────────────────────────

    @CircuitBreaker(name = "audit-service", fallbackMethod = "queryRouteHealthFallback")
    public QueryResponse.RouteHealthResponse queryRouteHealth(UUID tenantId, String window) {
        try {
            return rpc(RabbitTopology.RK_AUDIT_ROUTE_HEALTH,
                    new QueryRequest.RouteHealthQuery(tenantId, window),
                    QueryResponse.RouteHealthResponse.class);
        } catch (Exception e) {
            log.error("queryRouteHealth failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    @SuppressWarnings("unused")
    private QueryResponse.RouteHealthResponse queryRouteHealthFallback(UUID tenantId, String window, Throwable t) {
        log.warn("queryRouteHealth circuit open or timed out: {}", t.getMessage());
        return new QueryResponse.RouteHealthResponse(List.of());
    }
}
