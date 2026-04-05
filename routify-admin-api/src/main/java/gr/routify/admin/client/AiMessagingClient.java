package gr.routify.admin.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.common.client.AmqpServiceClientSupport;
import gr.routify.common.event.QueryRequest;
import gr.routify.common.event.QueryResponse;
import gr.routify.common.event.RabbitTopology;
import gr.routify.common.observability.RoutifyMetrics;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Admin-API messaging client for routify-ai-service.
 *
 * <p>Bridges the Routify Dashboard to the AI service over RabbitMQ using the same
 * Direct Reply-To RPC pattern used by all other service-to-service calls in Routify.
 *
 * <h2>Topology</h2>
 * <pre>
 * Dashboard → Admin-API (HTTP)
 *     │
 *     ▼
 * AiMessagingClient.testPolicy() / testModification() / evaluate()
 *     │  exchange: routify.ai-service
 *     │  routing key: ai.filter.evaluate | ai.modifier.evaluate
 *     ▼
 * routify-ai-service (RabbitMQ RPC listener)
 *     │  reply via amq.rabbitmq.reply-to
 *     ▼
 * Admin-API returns verdict to Dashboard
 * </pre>
 *
 * <h2>Fallback behaviour</h2>
 * All methods are protected by a Resilience4j circuit breaker named {@code "ai-service"}.
 * When the circuit is open or the AI service times out, the fallback returns a
 * safe passthrough/error response rather than propagating an exception to the client.
 */
@Slf4j
@Component
public class AiMessagingClient extends AmqpServiceClientSupport {

    public AiMessagingClient(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper, RoutifyMetrics metrics) {
        super(rabbitTemplate, objectMapper, RabbitTopology.EXCHANGE_AI_SERVICE, "admin-api", metrics);
    }

    // ─── AI Filter ────────────────────────────────────────────────────────────

    /**
     * Sends a real-time AI filter evaluation request to routify-ai-service.
     *
     * <p>Used by the dashboard's "Test Policy" dry-run panel and, when needed,
     * by any admin-initiated policy evaluation.
     *
     * @param request the AI filter evaluation request (see {@link QueryRequest.AiFilterEvaluate})
     * @return the LLM's filter verdict
     */
    @CircuitBreaker(name = "ai-service", fallbackMethod = "evaluateFilterFallback")
    public QueryResponse.AiFilterVerdict evaluateFilter(QueryRequest.AiFilterEvaluate request) {
        try {
            return rpc(RabbitTopology.RK_AI_FILTER_EVALUATE, request, QueryResponse.AiFilterVerdict.class);
        } catch (Exception e) {
            log.error("evaluateFilter RPC failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    @SuppressWarnings("unused")
    private QueryResponse.AiFilterVerdict evaluateFilterFallback(
            QueryRequest.AiFilterEvaluate request, Throwable t) {
        log.warn("evaluateFilter circuit open or timed out: {}", t.getMessage());
        return QueryResponse.AiFilterVerdict.fallback("ALLOW",
                "AI service unavailable — fallback applied", 0L);
    }

    // ─── AI Modifier ──────────────────────────────────────────────────────────

    /**
     * Sends a real-time AI modification evaluation request to routify-ai-service.
     *
     * <p>Used by the dashboard's "Test Modification" dry-run panel.
     *
     * @param request the AI modifier evaluation request (see {@link QueryRequest.AiModifierEvaluate})
     * @return the LLM's mutation verdict
     */
    @CircuitBreaker(name = "ai-service", fallbackMethod = "evaluateModifierFallback")
    public QueryResponse.AiModifierVerdict evaluateModifier(QueryRequest.AiModifierEvaluate request) {
        try {
            return rpc(RabbitTopology.RK_AI_MODIFIER_EVALUATE, request, QueryResponse.AiModifierVerdict.class);
        } catch (Exception e) {
            log.error("evaluateModifier RPC failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    @SuppressWarnings("unused")
    private QueryResponse.AiModifierVerdict evaluateModifierFallback(
            QueryRequest.AiModifierEvaluate request, Throwable t) {
        log.warn("evaluateModifier circuit open or timed out: {}", t.getMessage());
        return QueryResponse.AiModifierVerdict.passthrough(
                "AI service unavailable — passthrough applied", 0L);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Builds an {@link QueryRequest.AiFilterEvaluate} for a dashboard dry-run test-policy call.
     * userId / userRole / correlationId are irrelevant for test evaluations — passed as null.
     */
    public static QueryRequest.AiFilterEvaluate buildFilterEvaluateRequest(
            String routeId,
            String routeName,
            String tenantId,
            String policyDescription,
            String evaluationMode,
            boolean includeBody,
            int maxBodyBytes,
            String fallbackAction,
            double confidenceThreshold,
            boolean cacheEnabled,
            int cacheTtlSeconds,
            String method,
            String path,
            String queryString,
            String clientIp,
            Map<String, String> headers,
            String bodyExcerpt) {
        return new QueryRequest.AiFilterEvaluate(
                routeId, routeName, tenantId,
                policyDescription, evaluationMode, includeBody, maxBodyBytes,
                fallbackAction, confidenceThreshold, cacheEnabled, cacheTtlSeconds,
                method, path, queryString, clientIp, headers, bodyExcerpt,
                null,   // userId      — not available in dry-run
                null,   // userRole    — not available in dry-run
                null    // correlationId — generated by AmqpServiceClientSupport
        );
    }

    /**
     * Builds an {@link QueryRequest.AiModifierEvaluate} for a dashboard dry-run test-modification call.
     * timeoutMs / correlationId are irrelevant for test evaluations — passed as 0 / null.
     */
    public static QueryRequest.AiModifierEvaluate buildModifierEvaluateRequest(
            String routeId,
            String routeName,
            String tenantId,
            String modificationPrompt,
            String targetFields,
            String modelId,
            double temperature,
            int maxTokens,
            String fallbackBehavior,
            boolean includeBody,
            int maxBodyBytes,
            boolean cacheEnabled,
            int cacheTtlSeconds,
            String method,
            String path,
            String queryString,
            String clientIp,
            Map<String, String> headers,
            String bodyBase64) {
        return new QueryRequest.AiModifierEvaluate(
                routeId, routeName, tenantId,
                modificationPrompt, targetFields, modelId, temperature, maxTokens,
                fallbackBehavior, includeBody, maxBodyBytes, cacheEnabled, cacheTtlSeconds,
                0,      // timeoutMs — use service default for dry-run
                method, path, queryString, clientIp, headers, bodyBase64,
                null    // correlationId — generated by AmqpServiceClientSupport
        );
    }
}

