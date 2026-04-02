package gr.routify.admin.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.common.event.RabbitTopology;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Admin-API messaging client for audit log and request log queries in routify-audit-service.
 *
 * <p>All audit queries use RabbitMQ request/reply. Audit data is immutable —
 * there are no command operations from the dashboard.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditMessagingClient {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper   objectMapper;

    // ─── Audit Event Queries ──────────────────────────────────────────────────

    @CircuitBreaker(name = "audit-service", fallbackMethod = "queryAuditEventsFallback")
    public Map<String, Object> queryAuditEvents(UUID tenantId, String eventType,
                                                String aggregateType, String aggregateId,
                                                String from, String to, int page, int size) {
        try {
            var req = new java.util.LinkedHashMap<String, Object>();
            req.put("tenantId",      tenantId != null ? tenantId.toString() : "");
            req.put("eventType",     eventType);
            req.put("aggregateType", aggregateType);
            req.put("aggregateId",   aggregateId);
            req.put("from",          from);
            req.put("to",            to);
            req.put("page",          page);
            req.put("size",          size);
            return rpcAuditService(RabbitTopology.RK_AUDIT_EVENTS_QUERY, req);
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
            var req = new java.util.LinkedHashMap<String, Object>();
            req.put("tenantId", tenantId != null ? tenantId.toString() : "");
            req.put("routeId",  routeId != null ? routeId.toString() : null);
            req.put("from",     from);
            req.put("to",       to);
            req.put("page",     page);
            req.put("size",     size);
            return rpcAuditService(RabbitTopology.RK_AUDIT_REQUESTS_QUERY, req);
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
            var req = Map.of(
                    "tenantId", tenantId != null ? tenantId.toString() : "",
                    "routeId",  routeId.toString()
            );
            return rpcAuditService(RabbitTopology.RK_AUDIT_REQUESTS_STATS, req);
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
            var req = new java.util.LinkedHashMap<String, Object>();
            req.put("tenantId", tenantId != null ? tenantId.toString() : "");
            req.put("routeId",  routeId != null ? routeId.toString() : null);
            req.put("page",     page);
            req.put("size",     size);
            return rpcAuditService(RabbitTopology.RK_AUDIT_REPLAY_FAILED_QUERY, req);
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
            var req = new java.util.LinkedHashMap<String, Object>();
            req.put("tenantId", tenantId != null ? tenantId.toString() : "");
            req.put("routeId",  routeId != null ? routeId.toString() : null);
            req.put("page",     page);
            req.put("size",     size);
            return rpcAuditService(RabbitTopology.RK_AUDIT_REPLAY_PENDING_QUERY, req);
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
            var req = Map.of("tenantId", tenantId != null ? tenantId.toString() : "");
            return rpcAuditService(RabbitTopology.RK_AUDIT_REPLAY_STATS, req);
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
            var req = Map.of(
                    "id",       id.toString(),
                    "tenantId", tenantId != null ? tenantId.toString() : ""
            );
            return rpcAuditService(RabbitTopology.RK_AUDIT_REPLAY_SINGLE, req);
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
            var req = Map.of(
                    "tenantId", tenantId != null ? tenantId.toString() : "",
                    "limit",    limit
            );
            return rpcAuditService(RabbitTopology.RK_AUDIT_REPLAY_BULK, req);
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

    // ─── Private helpers ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> rpcAuditService(String routingKey, Object requestBody) throws Exception {
        String body = objectMapper.writeValueAsString(requestBody);
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        Message msg = MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                .andProperties(props).build();
        Message reply = rabbitTemplate.sendAndReceive(
                RabbitTopology.EXCHANGE_AUDIT_SERVICE, routingKey, msg);
        if (reply == null) return Map.of("error", "audit-service unavailable");
        return objectMapper.readValue(
                new String(reply.getBody(), StandardCharsets.UTF_8), new TypeReference<>() {});
    }
}

