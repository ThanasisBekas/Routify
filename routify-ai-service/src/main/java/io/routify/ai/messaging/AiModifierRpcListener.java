package io.routify.ai.messaging;

import io.routify.ai.dto.AiModificationRequest;
import io.routify.ai.dto.AiModificationResponse;
import io.routify.ai.service.AiModifierEvaluationService;
import io.routify.common.event.QueryRequest;
import io.routify.common.event.QueryResponse;
import io.routify.common.event.RabbitTopology;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * RabbitMQ RPC listener for AI Modification Filter requests from the API Gateway.
 *
 * <h3>Role in the RPC pattern</h3>
 * This class is the <em>server side</em> of the Direct Reply-To RPC call:
 * <ol>
 *   <li>The gateway publishes a {@link QueryRequest.AiModifierEvaluate} message to the
 *       {@code routify.ai-service} exchange with routing key {@code ai.modifier.evaluate}.</li>
 *   <li>Spring AMQP delivers it to this listener via the
 *       {@code routify.ai-service.modifier.evaluate} queue.</li>
 *   <li>This listener calls {@link AiModifierEvaluationService#evaluate} synchronously.</li>
 *   <li>The return value is automatically serialised to JSON and published back to
 *       the gateway's {@code amq.rabbitmq.reply-to} address.</li>
 * </ol>
 *
 * <h3>Error handling</h3>
 * The {@link AiModifierEvaluationService} is designed to NEVER throw — it always returns
 * a passthrough result on any failure. This ensures the gateway always receives a reply
 * within the configured timeout, preventing hanging on the request pipeline.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiModifierRpcListener {

    private final AiModifierEvaluationService modifierService;

    /**
     * Handles AI modification evaluation requests from the API Gateway.
     *
     * @param request the AI modifier evaluation request from the gateway
     * @return the mutation verdict to send back — never null (passthrough on error)
     */
    @RabbitListener(queues = RabbitTopology.QUEUE_AI_MODIFIER_EVALUATE)
    public QueryResponse.AiModifierVerdict handleModify(QueryRequest.AiModifierEvaluate request) {
        long startMs = System.currentTimeMillis();

        log.debug("RabbitMQ AI modifier request: routeId={} targets={} correlationId={}",
                request.routeId(), request.targetFields(), request.correlationId());

        try {
            AiModificationRequest serviceRequest = toServiceRequest(request);
            AiModificationResponse serviceResponse = modifierService.evaluate(serviceRequest);
            QueryResponse.AiModifierVerdict verdict = toWireVerdict(serviceResponse);

            log.info("RabbitMQ AI modifier response: routeId={} applied={} type={} latencyMs={}",
                    request.routeId(), verdict.mutationApplied(), verdict.mutationType(),
                    System.currentTimeMillis() - startMs);

            return verdict;

        } catch (Exception e) {
            long latency = System.currentTimeMillis() - startMs;
            log.error("Unexpected error in AI modifier RPC listener: routeId={} error={}",
                    request.routeId(), e.getMessage(), e);
            return QueryResponse.AiModifierVerdict.passthrough(
                    "Internal AI modifier error — passthrough applied", latency);
        }
    }

    // ─── DTO translation ─────────────────────────────────────────────────────

    private AiModificationRequest toServiceRequest(QueryRequest.AiModifierEvaluate r) {
        var modifierConfig = new AiModificationRequest.AiModifierConfig(
                r.modificationPrompt(),
                r.targetFields(),
                r.modelId(),
                r.temperature(),
                r.maxTokens(),
                r.fallbackBehavior(),
                r.includeBody(),
                r.maxBodyBytes(),
                r.cacheEnabled(),
                r.cacheTtlSeconds()
        );

        var requestContext = new AiModificationRequest.RequestContext(
                r.method(),
                r.path(),
                r.queryString(),
                r.clientIp(),
                r.headers() != null ? r.headers() : Map.of(),
                r.bodyBase64()
        );

        return new AiModificationRequest(
                r.routeId(),
                r.routeName(),
                r.tenantId(),
                modifierConfig,
                requestContext
        );
    }

    private QueryResponse.AiModifierVerdict toWireVerdict(AiModificationResponse r) {
        return new QueryResponse.AiModifierVerdict(
                r.mutationId(),
                r.mutationApplied(),
                r.mutationType().name(),
                r.mutatedHeaders() != null ? r.mutatedHeaders() : Map.of(),
                r.mutatedBody(),
                r.reason(),
                r.cached(),
                r.latencyMs()
        );
    }
}

