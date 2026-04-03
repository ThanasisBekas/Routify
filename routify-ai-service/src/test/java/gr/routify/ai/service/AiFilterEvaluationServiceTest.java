package gr.routify.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.ai.dto.RouteEvaluationRequest;
import gr.routify.ai.dto.RouteEvaluationResponse;
import gr.routify.ai.metrics.AiFilterMetrics;
import gr.routify.ai.prompt.PromptBuilderService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AiFilterEvaluationService}.
 *
 * <p>Uses Mockito to stub the {@link ChatClient} — no real LLM calls are made.
 * Tests cover: cache hits, structured JSON parsing, confidence threshold enforcement,
 * fallback on LLM failure, and BLOCK/ALLOW/FLAG verdict mapping.
 */
@ExtendWith(MockitoExtension.class)
class AiFilterEvaluationServiceTest {

    @Mock ChatClient             chatClient;
    @Mock ChatClient.ChatClientRequest chatClientRequest;
    @Mock ChatClient.CallResponseSpec callResponseSpec;
    @Mock PromptBuilderService   promptBuilder;
    @Mock VerdictCacheService    verdictCache;
    @Mock KafkaTemplate<String, Object> kafkaTemplate;

    private AiFilterEvaluationService service;
    private ObjectMapper objectMapper;
    private AiFilterMetrics metrics;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        metrics = new AiFilterMetrics(new SimpleMeterRegistry());
        service = new AiFilterEvaluationService(
                chatClient, promptBuilder, verdictCache, metrics, kafkaTemplate, objectMapper);

        // Default: prompt builder returns non-null strings
        when(promptBuilder.buildSystemPrompt()).thenReturn("You are a security filter...");
        when(promptBuilder.buildUserPrompt(any())).thenReturn("Evaluate this request...");
    }

    // ─── Cache hit ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Cache hit returns cached verdict without calling the LLM")
    void evaluate_cacheHit_returnsCachedVerdict() {
        var cached = RouteEvaluationResponse.allow("Cached — no issues", 0.99, true, 2, "e1");
        when(verdictCache.get(any())).thenReturn(Optional.of(cached));

        var result = service.evaluate(buildRequest("r1", 0.85));

        assertThat(result.cached()).isTrue();
        assertThat(result.action()).isEqualTo(RouteEvaluationResponse.VerdictAction.ALLOW);
        verifyNoInteractions(chatClient);
    }

    // ─── LLM ALLOW ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LLM ALLOW response maps to ALLOW verdict")
    void evaluate_llmAllow_returnsAllow() {
        when(verdictCache.get(any())).thenReturn(Optional.empty());
        stubLlmResponse("""
                {"action":"ALLOW","reason":"No issues detected","confidence":0.98}
                """);

        var result = service.callLlmWithCircuitBreaker(buildRequest("r1", 0.85), 0L);

        assertThat(result.action()).isEqualTo(RouteEvaluationResponse.VerdictAction.ALLOW);
        assertThat(result.isAllowed()).isTrue();
        assertThat(result.reason()).isEqualTo("No issues detected");
        assertThat(result.confidence()).isEqualTo(0.98);
    }

    // ─── LLM BLOCK ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LLM BLOCK response maps to BLOCK verdict")
    void evaluate_llmBlock_returnsBlock() {
        when(verdictCache.get(any())).thenReturn(Optional.empty());
        stubLlmResponse("""
                {"action":"BLOCK","reason":"SQL injection pattern in path","confidence":0.97}
                """);

        var result = service.callLlmWithCircuitBreaker(buildRequest("r1", 0.85), 0L);

        assertThat(result.action()).isEqualTo(RouteEvaluationResponse.VerdictAction.BLOCK);
        assertThat(result.isAllowed()).isFalse();
    }

    // ─── LLM FLAG ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LLM FLAG response maps to FLAG verdict (isAllowed=true)")
    void evaluate_llmFlag_returnsFlag() {
        stubLlmResponse("""
                {"action":"FLAG","reason":"Unusual user agent","confidence":0.72}
                """);

        var result = service.callLlmWithCircuitBreaker(buildRequest("r1", 0.60), 0L);

        assertThat(result.action()).isEqualTo(RouteEvaluationResponse.VerdictAction.FLAG);
        assertThat(result.isAllowed()).isTrue(); // FLAG = allowed + tagged
    }

    // ─── Confidence threshold ─────────────────────────────────────────────────

    @Test
    @DisplayName("Low-confidence BLOCK is overridden by fallback action (ALLOW)")
    void evaluate_lowConfidence_applyFallback() {
        stubLlmResponse("""
                {"action":"BLOCK","reason":"Maybe SQL injection","confidence":0.50}
                """);

        // confidenceThreshold=0.85, LLM returns 0.50 → fallback = ALLOW
        var result = service.callLlmWithCircuitBreaker(buildRequest("r1", 0.85), 0L);

        assertThat(result.action()).isEqualTo(RouteEvaluationResponse.VerdictAction.ALLOW);
        assertThat(result.reason()).contains("Confidence");
        assertThat(result.evaluationId()).isEqualTo("fallback");
    }

    // ─── Malformed LLM response ───────────────────────────────────────────────

    @Test
    @DisplayName("Malformed LLM JSON response applies fallback action")
    void evaluate_malformedLlmResponse_applyFallback() {
        stubLlmResponse("Sorry, I cannot evaluate this request."); // not JSON

        var result = service.callLlmWithCircuitBreaker(buildRequest("r1", 0.85), 0L);

        assertThat(result.action()).isEqualTo(RouteEvaluationResponse.VerdictAction.ALLOW); // fallbackAction=ALLOW
        assertThat(result.evaluationId()).isEqualTo("fallback");
    }

    @Test
    @DisplayName("LLM response with markdown code fence is parsed correctly")
    void evaluate_markdownWrappedJson_parsed() {
        stubLlmResponse("```json\n{\"action\":\"ALLOW\",\"reason\":\"Clean\",\"confidence\":0.95}\n```");

        var result = service.callLlmWithCircuitBreaker(buildRequest("r1", 0.85), 0L);

        assertThat(result.action()).isEqualTo(RouteEvaluationResponse.VerdictAction.ALLOW);
    }

    // ─── Circuit breaker fallback ─────────────────────────────────────────────

    @Test
    @DisplayName("Circuit breaker fallback returns configured fallback action")
    void llmFallback_returnsConfiguredFallbackAction() {
        var request = buildRequest("r1", 0.85); // fallbackAction=ALLOW
        var ex = new RuntimeException("LLM connection refused");

        var result = service.llmFallback(request, 0L, ex);

        assertThat(result.action()).isEqualTo(RouteEvaluationResponse.VerdictAction.ALLOW);
        assertThat(result.confidence()).isEqualTo(0.0);
        assertThat(result.reason()).contains("fallback");
    }

    @Test
    @DisplayName("Circuit breaker fallback with BLOCK policy returns BLOCK")
    void llmFallback_blockFallbackPolicy_returnsBlock() {
        var request = buildRequestWithFallback("r1", "BLOCK");
        var ex = new java.util.concurrent.TimeoutException("3s timeout");

        var result = service.llmFallback(request, 0L, ex);

        assertThat(result.action()).isEqualTo(RouteEvaluationResponse.VerdictAction.BLOCK);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void stubLlmResponse(String responseContent) {
        // Chain: chatClient.prompt().system().user().options().call().content()
        var promptSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var callSpec   = mock(ChatClient.CallResponseSpec.class);
        var userSpec   = mock(ChatClient.ChatClientRequestSpec.class);
        var optSpec    = mock(ChatClient.ChatClientRequestSpec.class);

        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.system(anyString())).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.options(any())).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(responseContent);
    }

    private RouteEvaluationRequest buildRequest(String routeId, double confidenceThreshold) {
        return new RouteEvaluationRequest(
                routeId, "test-route", "test-tenant",
                new RouteEvaluationRequest.AiFilterConfig(
                        "Block SQL injection", "SYNC", false, 512,
                        "ALLOW", confidenceThreshold, true, 30),
                new RouteEvaluationRequest.RequestContext(
                        "POST", "/api/orders", null, "127.0.0.1",
                        Map.of("Content-Type", "application/json"), null, null)
        );
    }

    private RouteEvaluationRequest buildRequestWithFallback(String routeId, String fallbackAction) {
        return new RouteEvaluationRequest(
                routeId, "test-route", "test-tenant",
                new RouteEvaluationRequest.AiFilterConfig(
                        "Block all requests", "SYNC", false, 512,
                        fallbackAction, 0.85, false, 30),
                new RouteEvaluationRequest.RequestContext(
                        "GET", "/api/test", null, "10.0.0.1",
                        Map.of(), null, null)
        );
    }
}

