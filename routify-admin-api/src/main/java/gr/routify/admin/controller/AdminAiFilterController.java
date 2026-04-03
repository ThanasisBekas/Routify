package gr.routify.admin.controller;

import gr.routify.admin.client.AiMessagingClient;
import gr.routify.admin.dto.TestPolicyRequest;
import gr.routify.common.event.QueryRequest;
import gr.routify.common.event.QueryResponse;
import gr.routify.common.web.RoutifyHeaders;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Admin controller for AI Filter operations — dashboard-facing endpoints.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code POST /api/v1/admin/ai-filter/test-policy}
 *       — dry-run policy test (dashboard "Test Policy" button)</li>
 * </ul>
 *
 * <h2>Transport</h2>
 * All calls are forwarded to {@code routify-ai-service} via RabbitMQ RPC
 * ({@code routify.ai-service} exchange, routing key {@code ai.filter.evaluate}).
 * The ai-service replies synchronously via Direct Reply-To within the configured timeout.
 *
 * <h2>Security</h2>
 * Protected by {@link gr.routify.admin.config.AdminSecurityConfig} — requires a
 * valid JWT. The endpoint is accessible under {@code /api/v1/admin/**} which
 * requires authentication.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/ai-filter")
@RequiredArgsConstructor
public class AdminAiFilterController {

    private final AiMessagingClient aiMessagingClient;

    // ─── Policy dry-run (dashboard test) ─────────────────────────────────────

    /**
     * Tests a natural-language AI filter policy against a synthetic sample request.
     *
     * <p>Called by the Routify Dashboard's "Test Policy" button in the AI filter
     * configuration UI, allowing operators to validate that a policy produces the
     * expected verdict before activating it on live traffic.
     *
     * <p>No caching is applied and no Kafka telemetry event is published for dry-run tests.
     *
     * @param tenantId the tenant context extracted from the JWT
     * @param request  contains the policy description and a synthetic request to test against
     * @return the LLM's verdict (action, reason, confidence) for the sample request
     */
    @PostMapping("/test-policy")
    public ResponseEntity<QueryResponse.AiFilterVerdict> testPolicy(
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @Valid @RequestBody TestPolicyRequest request) {

        log.debug("AI filter policy test: tenantId={} policy='{}'",
                tenantId, request.policyDescription());

        TestPolicyRequest.SampleRequest sample = request.sampleRequest();

        QueryRequest.AiFilterEvaluate rpcRequest = AiMessagingClient.buildFilterEvaluateRequest(
                "test-" + UUID.randomUUID(),          // synthetic routeId
                "policy-test",                         // synthetic routeName
                tenantId.toString(),
                request.policyDescription(),
                "SYNC",                                // always synchronous for dry-run
                sample.bodyExcerpt() != null,          // includeBody
                512,                                   // maxBodyBytes
                "ALLOW",                               // fallbackAction
                0.0,                                   // confidenceThreshold — always return raw verdict
                false,                                 // cacheEnabled — disabled for dry-run
                0,                                     // cacheTtlSeconds
                sample.method(),
                sample.path(),
                sample.queryString(),
                sample.clientIp() != null ? sample.clientIp() : "(test)",
                sample.headers() != null ? sample.headers() : Map.of(),
                sample.bodyExcerpt()
        );

        QueryResponse.AiFilterVerdict verdict = aiMessagingClient.evaluateFilter(rpcRequest);

        log.info("AI filter policy test result: tenantId={} action={} confidence={}",
                tenantId, verdict.action(), verdict.confidence());

        return ResponseEntity.ok(verdict);
    }
}

