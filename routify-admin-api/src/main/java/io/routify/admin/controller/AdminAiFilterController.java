package io.routify.admin.controller;

import io.routify.admin.client.AiMessagingClient;
import io.routify.admin.client.AuditMessagingClient;
import io.routify.admin.dto.TestPolicyRequest;
import io.routify.common.event.QueryRequest;
import io.routify.common.event.QueryResponse;
import io.routify.common.web.RoutifyHeaders;
import io.routify.admin.config.AdminSecurityConfig;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * Protected by {@link AdminSecurityConfig} — requires a
 * valid JWT. The endpoint is accessible under {@code /api/v1/admin/**} which
 * requires authentication.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/ai-filter")
@RequiredArgsConstructor
public class AdminAiFilterController {

    private final AiMessagingClient aiMessagingClient;
    private final AuditMessagingClient auditMessagingClient;

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
    @PreAuthorize("hasAuthority('AI_POLICY_WRITE') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
    public ResponseEntity<QueryResponse.AiFilterVerdict> testPolicy(
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @Valid @RequestBody TestPolicyRequest request) {

        log.debug("AI filter policy test: tenantId={} policy='{}'",
                tenantId, request.policyDescription());

        TestPolicyRequest.SampleRequest sample = request.sampleRequest();

        // Use promptOverride if provided (playground sends draft prompt text here)
        String effectivePolicy = (request.promptOverride() != null && !request.promptOverride().isBlank())
                ? request.promptOverride()
                : request.policyDescription();

        QueryRequest.AiFilterEvaluate rpcRequest = AiMessagingClient.buildFilterEvaluateRequest(
                "test-" + UUID.randomUUID(),          // synthetic routeId
                "policy-test",                         // synthetic routeName
                tenantId.toString(),
                effectivePolicy,
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

    // ─── Decision Labelling ──────────────────────────────────────────────────

    /**
     * Labels an AI filter decision as correct/incorrect/unclear for ground-truth feedback.
     * Updates accuracy scoring on the associated prompt version.
     */
    @PostMapping("/decisions/{evaluationId}/label")
    @PreAuthorize("hasAuthority('AI_POLICY_WRITE') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
    public ResponseEntity<QueryResponse.AiDecisionLabelResult> labelDecision(
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @PathVariable String evaluationId,
            @RequestBody LabelRequest body) {
        log.debug("Label AI decision: evaluationId={} label={}", evaluationId, body.label());
        var result = auditMessagingClient.labelDecision(evaluationId, tenantId, body.label());
        return ResponseEntity.ok(result);
    }

    /** Request body for decision labelling. */
    public record LabelRequest(String label) {}
}
