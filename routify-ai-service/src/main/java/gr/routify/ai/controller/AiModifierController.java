package gr.routify.ai.controller;

import gr.routify.ai.dto.AiModificationRequest;
import gr.routify.ai.dto.AiModificationResponse;
import gr.routify.ai.dto.TestModificationRequest;
import gr.routify.ai.service.AiModifierEvaluationService;
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
 * REST controller exposing the AI Modification Filter evaluation API.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code POST /api/v1/ai-modifier/evaluate}      — real-time mutation (gateway calls this)</li>
 *   <li>{@code POST /api/v1/ai-modifier/test-modification} — dry-run (dashboard calls this)</li>
 *   <li>{@code GET  /api/v1/ai-modifier/health}        — liveness probe</li>
 * </ul>
 *
 * <h2>Security</h2>
 * Kubernetes ClusterIP only — not reachable from outside the cluster.
 * The gateway calls this endpoint over inter-service JWT (same pattern as AiFilterController).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai-modifier")
@RequiredArgsConstructor
public class AiModifierController {

    private final AiModifierEvaluationService modifierService;

    // ─── Real-time mutation ───────────────────────────────────────────────────

    /**
     * Evaluates an intercepted route request for AI-driven mutation.
     *
     * <p>Called by {@code AiModifierGatewayFilterFactory} in {@code routify-api-gateway}
     * for every request that hits a route with an {@code AI_MODIFIER} filter attached.
     *
     * @param correlationId propagated correlation ID from the original client request
     * @param request       the full modification request payload
     * @return 200 with the mutation result — never 4xx/5xx (service always returns a result)
     */
    @PostMapping("/evaluate")
    public ResponseEntity<AiModificationResponse> evaluate(
            @RequestHeader(value = RoutifyHeaders.CORRELATION_ID, required = false) String correlationId,
            @RequestHeader(value = RoutifyHeaders.TENANT_ID,      required = false) String tenantId,
            @Valid @RequestBody AiModificationRequest request) {

        log.debug("AI modifier evaluation: correlationId={} routeId={} targets={}",
                correlationId, request.routeId(), request.modifierConfig().targetFields());

        AiModificationResponse result = modifierService.evaluate(request);

        return ResponseEntity.ok()
                .header(RoutifyHeaders.CORRELATION_ID, correlationId != null ? correlationId : "")
                .body(result);
    }

    // ─── Dry-run (dashboard test) ─────────────────────────────────────────────

    /**
     * Tests a modification prompt against a synthetic sample request without activating
     * any filter on a live route.
     *
     * <p>Called by the Routify Dashboard's "Test Modification" panel, allowing operators
     * to validate that a modification prompt produces the expected mutation before going live.
     *
     * <p>No caching is applied and no Kafka telemetry event is published.
     *
     * @param request contains the modification prompt and a synthetic request to test against
     * @return the mutation result showing what was changed (or passthrough if no change needed)
     */
    @PostMapping("/test-modification")
    public ResponseEntity<AiModificationResponse> testModification(
            @Valid @RequestBody TestModificationRequest request) {

        log.debug("AI modifier dry-run: prompt='{}'", request.modificationPrompt());

        // Build a synthetic evaluation request with caching disabled
        var testConfig = new AiModificationRequest.AiModifierConfig(
                request.modificationPrompt(),
                request.targetFields() != null ? request.targetFields() : "BODY",
                null,  // use service default model
                0.1,   // standard temperature
                1024,  // standard max tokens
                "PASSTHROUGH",
                request.sampleRequest().body() != null,
                2048,
                false, // caching disabled for test runs
                0
        );

        // Base64-encode the sample body for the prompt
        String bodyBase64 = null;
        if (request.sampleRequest().body() != null && !request.sampleRequest().body().isBlank()) {
            bodyBase64 = Base64.getEncoder().encodeToString(
                    request.sampleRequest().body().getBytes(StandardCharsets.UTF_8));
        }

        var requestContext = new AiModificationRequest.RequestContext(
                request.sampleRequest().method(),
                request.sampleRequest().path(),
                null,
                "(test)",
                request.sampleRequest().headers() != null ? request.sampleRequest().headers() : Map.of(),
                bodyBase64
        );

        var syntheticRequest = new AiModificationRequest(
                "test-" + UUID.randomUUID(),
                "modifier-test",
                "test-tenant",
                testConfig,
                requestContext
        );

        AiModificationResponse result = modifierService.evaluate(syntheticRequest);
        return ResponseEntity.ok(result);
    }

    // ─── Liveness ─────────────────────────────────────────────────────────────

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("AI modifier service is running");
    }
}

