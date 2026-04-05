package io.routify.ai.messaging;

import io.routify.ai.dto.RouteEvaluationRequest;
import io.routify.ai.dto.RouteEvaluationResponse;
import io.routify.ai.service.AiFilterEvaluationService;
import io.routify.common.event.QueryRequest;
import io.routify.common.event.QueryResponse;
import io.routify.common.event.RabbitTopology;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * RabbitMQ RPC listener for AI filter evaluation requests from the API Gateway.
 *
 * <h3>Role in the RPC pattern</h3>
 * This class is the <em>server side</em> of the Direct Reply-To RPC call:
 * <ol>
 *   <li>The gateway publishes a {@link QueryRequest.AiFilterEvaluate} message to the
 *       {@code routify.ai-service} exchange with routing key {@code ai.filter.evaluate}.</li>
 *   <li>Spring AMQP delivers it to this listener via the
 *       {@code routify.ai-service.filter.evaluate} queue.</li>
 *   <li>This listener calls {@link AiFilterEvaluationService#evaluate} synchronously
 *       (blocking is fine here — this is a standard Tomcat servlet thread, not a Netty event loop).</li>
 *   <li>The return value is automatically serialised to JSON and published back to
 *       the gateway's {@code amq.rabbitmq.reply-to} address using the original
 *       {@code correlationId}. Spring AMQP handles this transparently.</li>
 * </ol>
 *
 * <h3>Thread model</h3>
 * The AMQP listener container uses a thread pool configured in {@code application.yml}
 * ({@code spring.rabbitmq.listener.simple.concurrency}). Each thread processes one
 * request at a time (blocking on the LLM call is acceptable here). For high throughput,
 * increase the concurrency or add more AI service pod replicas.
 *
 * <h3>Error handling</h3>
 * Exceptions thrown from this method cause Spring AMQP to nack the message.
 * However, the {@link AiFilterEvaluationService} is designed to NEVER throw — it always
 * returns a fallback verdict on any failure. This ensures the gateway always receives
 * a reply within the configured timeout, preventing it from hanging.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiFilterRpcListener {

    private final AiFilterEvaluationService evaluationService;

    /**
     * Handles AI filter evaluation requests from the API Gateway.
     *
     * <p>Translates the incoming {@link QueryRequest.AiFilterEvaluate} wire format
     * (from {@code routify-common}) into the service-layer DTO, calls the evaluation
     * service, and translates the response back to the wire format.
     *
     * <p>The return value is automatically sent as the RPC reply to the gateway's
     * {@code replyTo} address (Direct Reply-To) with the original {@code correlationId}.
     *
     * @param request the AI filter evaluation request from the gateway
     * @return the verdict to send back — never null (fallback is applied on error)
     */
    @RabbitListener(queues = RabbitTopology.QUEUE_AI_FILTER_EVALUATE)
    public QueryResponse.AiFilterVerdict handleEvaluate(QueryRequest.AiFilterEvaluate request) {
        long startMs = System.currentTimeMillis();

        log.debug("RabbitMQ AI filter request: routeId={} mode={} correlationId={}",
                request.routeId(), request.evaluationMode(), request.correlationId());

        try {
            // Translate wire format → service DTO
            RouteEvaluationRequest serviceRequest = toServiceRequest(request);

            // Delegate to the service — this call handles caching, circuit breaking,
            // LLM invocation, and fallback internally. It NEVER throws.
            RouteEvaluationResponse serviceResponse = evaluationService.evaluate(serviceRequest);

            // Translate service response → wire format
            QueryResponse.AiFilterVerdict verdict = toWireVerdict(serviceResponse);

            log.info("RabbitMQ AI filter response: routeId={} action={} confidence={} cached={} latencyMs={}",
                    request.routeId(), verdict.action(), verdict.confidence(),
                    verdict.cached(), System.currentTimeMillis() - startMs);

            return verdict;

        } catch (Exception e) {
            // Belt-and-suspenders: the service should never reach here,
            // but if it does we still reply with a fallback rather than nacking.
            long latency = System.currentTimeMillis() - startMs;
            log.error("Unexpected error in AI filter RPC listener: routeId={} error={}",
                    request.routeId(), e.getMessage(), e);

            return QueryResponse.AiFilterVerdict.fallback(
                    request.fallbackAction() != null ? request.fallbackAction() : "ALLOW",
                    "Internal AI filter error — fallback applied",
                    latency);
        }
    }

    // ─── DTO translation ─────────────────────────────────────────────────────

    /**
     * Translates the RabbitMQ wire format ({@link QueryRequest.AiFilterEvaluate})
     * into the service-layer DTO ({@link RouteEvaluationRequest}).
     *
     * <p>The wire format is a flat record (RabbitMQ-friendly); the service DTO uses
     * nested records for cleaner domain modelling. This translation is the only place
     * that bridges the two representations.
     */
    private RouteEvaluationRequest toServiceRequest(QueryRequest.AiFilterEvaluate r) {
        var filterConfig = new RouteEvaluationRequest.AiFilterConfig(
                r.policyDescription(),
                r.evaluationMode(),
                r.includeBody(),
                r.maxBodyBytes(),
                r.fallbackAction(),
                r.confidenceThreshold(),
                r.cacheEnabled(),
                r.cacheTtlSeconds()
        );

        // Build user context if auth headers were propagated
        RouteEvaluationRequest.UserContext userContext = null;
        if (r.userId() != null && !r.userId().isBlank()) {
            userContext = new RouteEvaluationRequest.UserContext(
                    r.userId(), r.tenantId(), r.userRole(), null);
        }

        var requestContext = new RouteEvaluationRequest.RequestContext(
                r.method(),
                r.path(),
                r.queryString(),
                r.clientIp(),
                r.headers() != null ? r.headers() : Map.of(),
                r.bodyExcerpt(),
                userContext
        );

        return new RouteEvaluationRequest(
                r.routeId(),
                r.routeName(),
                r.tenantId(),
                filterConfig,
                requestContext
        );
    }

    /**
     * Translates the service-layer response ({@link RouteEvaluationResponse})
     * into the RabbitMQ wire format ({@link QueryResponse.AiFilterVerdict}).
     */
    private QueryResponse.AiFilterVerdict toWireVerdict(RouteEvaluationResponse r) {
        return new QueryResponse.AiFilterVerdict(
                r.action().name(),
                r.reason(),
                r.confidence(),
                r.isAllowed(),
                r.cached(),
                r.latencyMs(),
                r.evaluationId()
        );
    }
}

