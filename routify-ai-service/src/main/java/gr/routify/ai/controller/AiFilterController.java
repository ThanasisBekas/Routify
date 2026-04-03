package gr.routify.ai.controller;

import gr.routify.ai.dto.RouteEvaluationRequest;
import gr.routify.ai.dto.RouteEvaluationResponse;
import gr.routify.ai.dto.TestPolicyRequest;
import gr.routify.ai.service.AiFilterEvaluationService;
import gr.routify.common.web.RoutifyHeaders;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller exposing the AI filter evaluation API to the Routify API Gateway.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code POST /api/v1/ai-filter/evaluate}   — real-time request evaluation (gateway calls this)</li>
 *   <li>{@code POST /api/v1/ai-filter/test-policy} — dry-run policy testing (dashboard calls this)</li>
 *   <li>{@code GET  /api/v1/ai-filter/health}      — simple liveness probe (supplements actuator)</li>
 * </ul>
 *
 * <h2>Security</h2>
 * This service is a Kubernetes ClusterIP — it is not reachable from outside the cluster.
 * The {@link gr.routify.ai.config.AiSecurityConfig} trusts the gateway's pre-auth headers
 * ({@code X-Auth-*}) injected by the gateway's {@code JwtAuthGatewayFilterFactory}.
 * Direct calls without these headers are accepted in internal-only mode (the endpoints are
 * protected at the network layer by K8s NetworkPolicy, not at the HTTP layer).
 *
 * <h2>Error handling</h2>
 * Delegates to {@link gr.routify.common.exception.GlobalExceptionHandler} for
 * consistent RFC 9457 Problem JSON error responses.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai-filter")
@RequiredArgsConstructor
public class AiFilterController {

    private final AiFilterEvaluationService evaluationService;

    // ─── Real-time evaluation ─────────────────────────────────────────────────

    /**
     * Evaluates an intercepted route request against the configured AI filter policy.
     *
     * <p>Called by {@code AiGatewayFilterFactory} in {@code routify-api-gateway} for every
     * request that hits a route with an {@code AI_FILTER} filter attached.
     *
     * <p>The {@code X-Correlation-Id} header is forwarded from the original request
     * for distributed trace correlation across the gateway → AI service hop.
     *
     * @param correlationId propagated correlation ID from the original client request
     * @param request       the full evaluation request payload
     * @return 200 with the verdict — never 4xx/5xx (service always returns a verdict)
     */
    @PostMapping("/evaluate")
    public ResponseEntity<RouteEvaluationResponse> evaluate(
            @RequestHeader(value = RoutifyHeaders.CORRELATION_ID, required = false) String correlationId,
            @RequestHeader(value = RoutifyHeaders.TENANT_ID,      required = false) String tenantId,
            @Valid @RequestBody RouteEvaluationRequest request) {

        log.debug("AI filter evaluation: correlationId={} routeId={} mode={}",
                correlationId, request.routeId(), request.filterConfig().evaluationMode());

        RouteEvaluationResponse verdict = evaluationService.evaluate(request);

        return ResponseEntity.ok()
                .header(RoutifyHeaders.CORRELATION_ID, correlationId != null ? correlationId : "")
                .body(verdict);
    }

    // ─── Policy dry-run (dashboard test) ─────────────────────────────────────

    /**
     * Tests a natural-language policy against a synthetic sample request without
     * activating any filter on a live route.
     *
     * <p>Called by the Routify Dashboard's "Test Policy" button in the AI filter
     * configuration UI, allowing operators to validate that a policy produces the
     * expected verdict before going live.
     *
     * <p>No caching is applied and no Kafka telemetry event is published.
     * The evaluation uses a minimal {@link RouteEvaluationRequest.AiFilterConfig}
     * with defaults except for the provided {@code policyDescription}.
     *
     * @param request contains the policy description and a synthetic request to test against
     * @return the LLM's verdict (action, reason, confidence) for the sample request
     */
    @PostMapping("/test-policy")
    public ResponseEntity<RouteEvaluationResponse> testPolicy(
            @Valid @RequestBody TestPolicyRequest request) {

        log.debug("AI filter policy test: policy='{}'", request.policyDescription());

        // Build a synthetic evaluation request with caching disabled
        var testConfig = new RouteEvaluationRequest.AiFilterConfig(
                request.policyDescription(),
                "SYNC",
                request.sampleRequest().bodyExcerpt() != null,
                512,
                "ALLOW",
                0.0,   // no confidence threshold in test mode — always return raw verdict
                false, // caching disabled for test runs
                0
        );

        var syntheticRequest = new RouteEvaluationRequest(
                "test-" + UUID.randomUUID(),
                "policy-test",
                "test-tenant",
                testConfig,
                request.sampleRequest()
        );

        RouteEvaluationResponse verdict = evaluationService.evaluate(syntheticRequest);
        return ResponseEntity.ok(verdict);
    }

    // ─── Liveness ─────────────────────────────────────────────────────────────

    /**
     * Simple liveness check endpoint.
     * Use {@code /actuator/health} for full Spring Boot health details.
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("AI filter service is running");
    }
}

