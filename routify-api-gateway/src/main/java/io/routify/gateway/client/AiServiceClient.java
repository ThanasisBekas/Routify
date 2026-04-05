package io.routify.gateway.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.routify.common.client.AmqpServiceClientSupport;
import io.routify.common.event.QueryRequest;
import io.routify.common.event.QueryResponse;
import io.routify.common.event.RabbitTopology;
import io.routify.common.exception.RoutifyException;
import io.routify.gateway.filter.AiGatewayFilterFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ RPC client for AI filter evaluation requests.
 *
 * <p>The gateway calls this client synchronously from within the
 * {@link AiGatewayFilterFactory} reactive filter pipeline.
 * Because Spring Cloud Gateway is fully reactive (WebFlux / Netty), the blocking
 * {@code sendAndReceive} call is always offloaded to a bounded elastic scheduler thread
 * via {@code Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())} — it never
 * blocks the Netty event loop.
 *
 * <h3>Topology</h3>
 * <pre>
 * Gateway (producer)
 *   │  exchange: routify.ai-service
 *   │  routing-key: ai.filter.evaluate
 *   │  replyTo: amq.rabbitmq.reply-to  (Direct Reply-To pseudo-queue)
 *   ▼
 * AI Service (consumer/responder)
 *   queue: routify.ai-service.filter.evaluate
 *   @RabbitListener → AiFilterRpcListener → AiFilterEvaluationService → reply
 * </pre>
 *
 * <h3>Timeout</h3>
 * The underlying {@link RabbitTemplate} is configured with
 * {@link RabbitTopology#AI_FILTER_REPLY_TIMEOUT_MS} (3.5s). On timeout,
 * {@code sendAndReceive} returns {@code null}, and this client throws a
 * {@link RoutifyException.GatewayError} which the filter
 * catches and converts to a fallback verdict.
 */
@Slf4j
@Component
public class AiServiceClient extends AmqpServiceClientSupport {

    public AiServiceClient(@Qualifier("aiServiceRabbitTemplate") RabbitTemplate aiServiceRabbitTemplate,
                           ObjectMapper objectMapper) {
        super(aiServiceRabbitTemplate, objectMapper, RabbitTopology.EXCHANGE_AI_SERVICE, "api-gateway");
    }

    /**
     * Sends a synchronous AI filter evaluation request to routify-ai-service via RabbitMQ RPC.
     *
     * <p><strong>Must NOT be called from the Netty event loop.</strong>
     * Always wrap in {@code Mono.fromCallable(() -> evaluate(...)).subscribeOn(Schedulers.boundedElastic())}.
     *
     * @param request the fully populated evaluation request
     * @return the AI service verdict (never null — throws on timeout/error)
     */
    public QueryResponse.AiFilterVerdict evaluate(QueryRequest.AiFilterEvaluate request) {
        log.debug("AI filter RPC → RabbitMQ: routeId={} policy='{}' mode={}",
                request.routeId(),
                request.policyDescription() != null
                        ? request.policyDescription().substring(0, Math.min(50, request.policyDescription().length()))
                        : "(null)",
                request.evaluationMode());

        return rpc(
                RabbitTopology.RK_AI_FILTER_EVALUATE,
                request,
                new TypeReference<QueryResponse.AiFilterVerdict>() {}
        );
    }

    /**
     * Sends a synchronous AI modification request to routify-ai-service via RabbitMQ RPC.
     *
     * <p><strong>Must NOT be called from the Netty event loop.</strong>
     * Always wrap in {@code Mono.fromCallable(() -> modify(...)).subscribeOn(Schedulers.boundedElastic())}.
     *
     * @param request the fully populated modification request (includes body bytes)
     * @return the AI service mutation verdict (never null — throws on timeout/error)
     */
    public QueryResponse.AiModifierVerdict modify(QueryRequest.AiModifierEvaluate request) {
        log.debug("AI modifier RPC → RabbitMQ: routeId={} targets={} includeBody={}",
                request.routeId(), request.targetFields(), request.includeBody());

        return rpc(
                RabbitTopology.RK_AI_MODIFIER_EVALUATE,
                request,
                new TypeReference<QueryResponse.AiModifierVerdict>() {}
        );
    }
}

