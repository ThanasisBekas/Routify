package gr.routify.admin.controller;

import gr.routify.admin.client.AiMessagingClient;
import gr.routify.admin.dto.TestModificationRequest;
import gr.routify.common.event.QueryRequest;
import gr.routify.common.event.QueryResponse;
import gr.routify.common.web.RoutifyHeaders;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * Admin controller for AI Modifier operations — dashboard-facing endpoints.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code POST /api/v1/admin/ai-modifier/test-modification}
 *       — dry-run modification test (dashboard "Test Modification" panel)</li>
 * </ul>
 *
 * <h2>Transport</h2>
 * All calls are forwarded to {@code routify-ai-service} via RabbitMQ RPC
 * ({@code routify.ai-service} exchange, routing key {@code ai.modifier.evaluate}).
 * The ai-service replies synchronously via Direct Reply-To within the configured timeout.
 *
 * <h2>Security</h2>
 * Protected by {@link gr.routify.admin.config.AdminSecurityConfig} — requires a
 * valid JWT. The endpoint is accessible under {@code /api/v1/admin/**} which
 * requires authentication.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/ai-modifier")
@RequiredArgsConstructor
public class AdminAiModifierController {

    private final AiMessagingClient aiMessagingClient;

    // ─── Dry-run modification test (dashboard) ────────────────────────────────

    /**
     * Tests a modification prompt against a synthetic sample request without activating
     * any filter on live traffic.
     *
     * <p>Called by the Routify Dashboard's "Test Modification" panel, allowing operators
     * to validate that a modification prompt produces the expected mutation before going live.
     *
     * <p>No caching is applied and no Kafka telemetry event is published for dry-run tests.
     *
     * @param tenantId the tenant context extracted from the JWT
     * @param request  contains the modification prompt and a synthetic request to test against
     * @return the mutation result showing what was changed (or passthrough if no change needed)
     */
    @PostMapping("/test-modification")
    public ResponseEntity<QueryResponse.AiModifierVerdict> testModification(
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @Valid @RequestBody TestModificationRequest request) {

        log.debug("AI modifier dry-run: tenantId={} prompt='{}'",
                tenantId, request.modificationPrompt());

        TestModificationRequest.SampleRequest sample = request.sampleRequest();

        // Base64-encode the raw body for the RPC request
        String bodyBase64 = null;
        if (sample.body() != null && !sample.body().isBlank()) {
            bodyBase64 = Base64.getEncoder().encodeToString(
                    sample.body().getBytes(StandardCharsets.UTF_8));
        }

        QueryRequest.AiModifierEvaluate rpcRequest = AiMessagingClient.buildModifierEvaluateRequest(
                "test-" + UUID.randomUUID(),                               // synthetic routeId
                "modifier-test",                                            // synthetic routeName
                tenantId.toString(),
                request.modificationPrompt(),
                request.targetFields() != null ? request.targetFields() : "BODY",
                null,                                                       // modelId — use service default
                0.1,                                                        // temperature
                1024,                                                       // maxTokens
                "PASSTHROUGH",                                              // fallbackBehavior
                sample.body() != null,                                      // includeBody
                2048,                                                       // maxBodyBytes
                false,                                                      // cacheEnabled — disabled for dry-run
                0,                                                          // cacheTtlSeconds
                sample.method(),
                sample.path(),
                null,                                                       // queryString
                "(test)",                                                   // clientIp
                sample.headers() != null ? sample.headers() : Map.of(),
                bodyBase64
        );

        QueryResponse.AiModifierVerdict verdict = aiMessagingClient.evaluateModifier(rpcRequest);

        log.info("AI modifier dry-run result: tenantId={} applied={} type={}",
                tenantId, verdict.mutationApplied(), verdict.mutationType());

        return ResponseEntity.ok(verdict);
    }
}

