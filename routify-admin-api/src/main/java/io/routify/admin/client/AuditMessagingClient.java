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

    // ─── Tenant Usage Analytics ──────────────────────────────────────────────

    @CircuitBreaker(name = "audit-service", fallbackMethod = "queryUsageHistoryFallback")
    public QueryResponse.UsageHistoryResult queryUsageHistory(UUID tenantId, int days) {
        return rpc(RabbitTopology.RK_AUDIT_USAGE_HISTORY,
                new QueryRequest.UsageHistory(tenantId, days),
                QueryResponse.UsageHistoryResult.class);
    }

    @SuppressWarnings("unused")
    private QueryResponse.UsageHistoryResult queryUsageHistoryFallback(UUID tenantId, int days, Throwable t) {
        log.warn("queryUsageHistory circuit open or timed out: {}", t.getMessage());
        return new QueryResponse.UsageHistoryResult(tenantId, List.of());
    }

    // ─── AI Prompt Version Management ────────────────────────────────────────

    @CircuitBreaker(name = "audit-service", fallbackMethod = "queryPromptVersionsFallback")
    public QueryResponse.PromptVersionsPage queryPromptVersions(UUID filterId, UUID tenantId, int page, int size) {
        return rpc(RabbitTopology.RK_AI_PROMPT_VERSIONS_QUERY,
                new QueryRequest.PromptVersionsQuery(filterId, tenantId, page, size),
                QueryResponse.PromptVersionsPage.class);
    }

    @SuppressWarnings("unused")
    private QueryResponse.PromptVersionsPage queryPromptVersionsFallback(
            UUID filterId, UUID tenantId, int page, int size, Throwable t) {
        log.warn("queryPromptVersions circuit open or timed out: {}", t.getMessage());
        return new QueryResponse.PromptVersionsPage(List.of(), 0L, 0, page, size);
    }

    @CircuitBreaker(name = "audit-service", fallbackMethod = "getPromptVersionFallback")
    public QueryResponse.PromptVersionDetail getPromptVersion(UUID id, UUID tenantId) {
        return rpc(RabbitTopology.RK_AI_PROMPT_VERSIONS_GET,
                new QueryRequest.PromptVersionGet(id, tenantId),
                QueryResponse.PromptVersionDetail.class);
    }

    @SuppressWarnings("unused")
    private QueryResponse.PromptVersionDetail getPromptVersionFallback(UUID id, UUID tenantId, Throwable t) {
        log.warn("getPromptVersion circuit open or timed out: {}", t.getMessage());
        throw new io.routify.common.exception.RoutifyException.GatewayError(
                "audit-service temporarily unavailable");
    }

    @CircuitBreaker(name = "audit-service", fallbackMethod = "savePromptVersionFallback")
    public QueryResponse.PromptVersionDetail savePromptVersion(
            UUID filterId, UUID tenantId, UUID versionId,
            String promptText, String description, String action, String requestedBy) {
        return rpc(RabbitTopology.RK_AI_PROMPT_VERSIONS_SAVE,
                new QueryRequest.PromptVersionSave(filterId, tenantId, versionId,
                        promptText, description, action, requestedBy),
                QueryResponse.PromptVersionDetail.class);
    }

    @SuppressWarnings("unused")
    private QueryResponse.PromptVersionDetail savePromptVersionFallback(
            UUID filterId, UUID tenantId, UUID versionId,
            String promptText, String description, String action, String requestedBy, Throwable t) {
        log.warn("savePromptVersion circuit open or timed out: {}", t.getMessage());
        throw new io.routify.common.exception.RoutifyException.GatewayError(
                "audit-service temporarily unavailable");
    }

    // ─── AI Decision Labelling ────────────────────────────────────────────────

    @CircuitBreaker(name = "audit-service", fallbackMethod = "labelDecisionFallback")
    public QueryResponse.AiDecisionLabelResult labelDecision(String evaluationId, UUID tenantId, String label) {
        return rpc(RabbitTopology.RK_AI_DECISION_LABEL,
                new QueryRequest.AiDecisionLabel(evaluationId, tenantId, label),
                QueryResponse.AiDecisionLabelResult.class);
    }

    @SuppressWarnings("unused")
    private QueryResponse.AiDecisionLabelResult labelDecisionFallback(
            String evaluationId, UUID tenantId, String label, Throwable t) {
        log.warn("labelDecision circuit open or timed out: {}", t.getMessage());
        return new QueryResponse.AiDecisionLabelResult(false, null, null);
    }

    // ─── AI Filter Stats (version-filtered) ──────────────────────────────────

    @CircuitBreaker(name = "audit-service", fallbackMethod = "queryAiFilterStatsFallback")
    public QueryResponse.AiFilterStatsResult queryAiFilterStats(
            UUID tenantId, UUID routeId, String from, String to) {
        return rpc(RabbitTopology.RK_AUDIT_AI_FILTER_STATS,
                new QueryRequest.AiFilterStatsQuery(tenantId, routeId, from, to),
                QueryResponse.AiFilterStatsResult.class);
    }

    @SuppressWarnings("unused")
    private QueryResponse.AiFilterStatsResult queryAiFilterStatsFallback(
            UUID tenantId, UUID routeId, String from, String to, Throwable t) {
        log.warn("queryAiFilterStats circuit open or timed out: {}", t.getMessage());
        return new QueryResponse.AiFilterStatsResult(tenantId, routeId, 0L, 0L, 0L, 0L, 0L, 0L, 0.0, 0L, 0L,
                from != null ? from : "", to != null ? to : "");
    }

    // ─── Time-Series Analytics (GraphQL Initiative 13) ────────────────────────

    @CircuitBreaker(name = "audit-service", fallbackMethod = "queryTimeSeriesFallback")
    public QueryResponse.TimeSeriesResult queryTimeSeries(
            UUID tenantId, UUID routeId, String from, String to,
            String granularity, java.util.List<String> metrics) {
        return rpc(RabbitTopology.RK_AUDIT_TIME_SERIES,
                new QueryRequest.TimeSeriesQuery(tenantId, routeId, from, to, granularity, metrics),
                QueryResponse.TimeSeriesResult.class);
    }

    @SuppressWarnings("unused")
    private QueryResponse.TimeSeriesResult queryTimeSeriesFallback(
            UUID tenantId, UUID routeId, String from, String to,
            String granularity, java.util.List<String> metrics, Throwable t) {
        log.warn("queryTimeSeries circuit open or timed out: {}", t.getMessage());
        return new QueryResponse.TimeSeriesResult(List.of());
    }

    // ─── Alerting Engine (Initiative 15) ──────────────────────────────────────

    @CircuitBreaker(name = "audit-service", fallbackMethod = "queryAlertRulesFallback")
    public QueryResponse.AlertRulesPage queryAlertRules(UUID tenantId, int page, int size) {
        return rpc(RabbitTopology.RK_ALERT_RULES_QUERY,
                new QueryRequest.AlertRulesQuery(tenantId, page, size),
                QueryResponse.AlertRulesPage.class);
    }

    @SuppressWarnings("unused")
    private QueryResponse.AlertRulesPage queryAlertRulesFallback(UUID tenantId, int page, int size, Throwable t) {
        log.warn("queryAlertRules circuit open or timed out: {}", t.getMessage());
        return new QueryResponse.AlertRulesPage(List.of(), 0L, 0, page, size);
    }

    @CircuitBreaker(name = "audit-service", fallbackMethod = "getAlertRuleFallback")
    public QueryResponse.AlertRuleDetail getAlertRule(UUID id, UUID tenantId) {
        return rpc(RabbitTopology.RK_ALERT_RULES_GET,
                new QueryRequest.AlertRuleGet(id, tenantId),
                QueryResponse.AlertRuleDetail.class);
    }

    @SuppressWarnings("unused")
    private QueryResponse.AlertRuleDetail getAlertRuleFallback(UUID id, UUID tenantId, Throwable t) {
        log.warn("getAlertRule circuit open or timed out: {}", t.getMessage());
        throw new io.routify.common.exception.RoutifyException.GatewayError(
                "audit-service temporarily unavailable");
    }

    @CircuitBreaker(name = "audit-service", fallbackMethod = "queryAlertEventsFallback")
    public QueryResponse.AlertEventsPage queryAlertEvents(UUID ruleId, UUID tenantId, int page, int size) {
        return rpc(RabbitTopology.RK_ALERT_EVENTS_QUERY,
                new QueryRequest.AlertEventsQuery(ruleId, tenantId, page, size),
                QueryResponse.AlertEventsPage.class);
    }

    @SuppressWarnings("unused")
    private QueryResponse.AlertEventsPage queryAlertEventsFallback(UUID ruleId, UUID tenantId,
                                                                     int page, int size, Throwable t) {
        log.warn("queryAlertEvents circuit open or timed out: {}", t.getMessage());
        return new QueryResponse.AlertEventsPage(List.of(), 0L, 0, page, size);
    }

    @CircuitBreaker(name = "audit-service", fallbackMethod = "alertRuleCommandFallback")
    public QueryResponse.AlertRuleDetail alertRuleCommand(
            String action, UUID tenantId, UUID ruleId,
            String name, String description, String metric, UUID routeId,
            String operator, java.math.BigDecimal threshold,
            Integer windowMinutes, Integer cooldownMinutes, String severity,
            Boolean enabled, Integer muteDurationMinutes, String requestedBy) {
        return rpc(RabbitTopology.RK_ALERT_RULES_COMMAND,
                new QueryRequest.AlertRuleCommand(action, tenantId, ruleId,
                        name, description, metric, routeId, operator, threshold,
                        windowMinutes, cooldownMinutes, severity, enabled,
                        muteDurationMinutes, requestedBy),
                QueryResponse.AlertRuleDetail.class);
    }

    @SuppressWarnings("unused")
    private QueryResponse.AlertRuleDetail alertRuleCommandFallback(
            String action, UUID tenantId, UUID ruleId,
            String name, String description, String metric, UUID routeId,
            String operator, java.math.BigDecimal threshold,
            Integer windowMinutes, Integer cooldownMinutes, String severity,
            Boolean enabled, Integer muteDurationMinutes, String requestedBy,
            Throwable t) {
        log.warn("alertRuleCommand circuit open or timed out: {}", t.getMessage());
        throw new io.routify.common.exception.RoutifyException.GatewayError(
                "audit-service temporarily unavailable");
    }
}
