package gr.routify.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.ai.dto.RouteEvaluationRequest;
import gr.routify.ai.dto.RouteEvaluationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link VerdictCacheService}.
 *
 * <p>Tests cache key generation, cache hit/miss behaviour, and error resilience
 * (cache failures must never block the evaluation path).
 */
@ExtendWith(MockitoExtension.class)
class VerdictCacheServiceTest {

    @Mock StringRedisTemplate    redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    private VerdictCacheService cacheService;
    private ObjectMapper        objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        cacheService = new VerdictCacheService(redisTemplate, objectMapper);
    }

    // ─── Key generation ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Cache key includes routeId and SHA-256 fingerprint")
    void buildKey_includesRouteIdAndFingerprint() {
        var request = buildRequest("my-route-id", "ALLOW", true);
        String key = cacheService.buildKey(request);

        assertThat(key).startsWith(VerdictCacheService.KEY_PREFIX + "my-route-id:");
        assertThat(key).hasSize(VerdictCacheService.KEY_PREFIX.length() + "my-route-id:".length() + 64); // sha256 hex = 64 chars
    }

    @Test
    @DisplayName("Different policies produce different cache keys")
    void buildKey_differentPoliciesProduceDifferentKeys() {
        var req1 = buildRequestWithPolicy("my-route", "Block SQL injection");
        var req2 = buildRequestWithPolicy("my-route", "Block XSS attacks");

        assertThat(cacheService.buildKey(req1)).isNotEqualTo(cacheService.buildKey(req2));
    }

    @Test
    @DisplayName("Identical requests produce identical cache keys (deterministic)")
    void buildKey_identicalRequestsProduceIdenticalKeys() {
        var req1 = buildRequest("route-1", "ALLOW", true);
        var req2 = buildRequest("route-1", "ALLOW", true);

        assertThat(cacheService.buildKey(req1)).isEqualTo(cacheService.buildKey(req2));
    }

    // ─── Cache miss ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Cache miss returns empty Optional")
    void get_cacheMiss_returnsEmpty() {
        when(valueOps.get(anyString())).thenReturn(null);

        Optional<RouteEvaluationResponse> result = cacheService.get(buildRequest("r1", "ALLOW", true));

        assertThat(result).isEmpty();
    }

    // ─── Cache hit ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Cache hit returns deserialized verdict")
    void get_cacheHit_returnsVerdict() throws Exception {
        var cached = RouteEvaluationResponse.allow("No issues", 0.99, true, 3, "eval-1");
        when(valueOps.get(anyString())).thenReturn(objectMapper.writeValueAsString(cached));

        Optional<RouteEvaluationResponse> result = cacheService.get(buildRequest("r1", "ALLOW", true));

        assertThat(result).isPresent();
        assertThat(result.get().action()).isEqualTo(RouteEvaluationResponse.VerdictAction.ALLOW);
        assertThat(result.get().confidence()).isEqualTo(0.99);
    }

    // ─── Cache disabled ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Cache is not consulted when cacheEnabled=false")
    void get_cacheDisabled_returnsEmpty() {
        var request = buildRequest("r1", "ALLOW", false); // cacheEnabled=false

        Optional<RouteEvaluationResponse> result = cacheService.get(request);

        assertThat(result).isEmpty();
        verifyNoInteractions(valueOps);
    }

    @Test
    @DisplayName("Cache write is skipped when cacheEnabled=false")
    void put_cacheDisabled_skipsRedisWrite() {
        var request = buildRequest("r1", "ALLOW", false);
        var verdict = RouteEvaluationResponse.allow("ok", 0.9, false, 100, "e1");

        cacheService.put(request, verdict);

        verifyNoInteractions(valueOps);
    }

    // ─── Resilience ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Redis read exception returns empty Optional (never blocks evaluation)")
    void get_redisException_returnsEmpty() {
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("Redis connection refused"));

        Optional<RouteEvaluationResponse> result = cacheService.get(buildRequest("r1", "ALLOW", true));

        assertThat(result).isEmpty(); // Must not propagate exception
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private RouteEvaluationRequest buildRequest(String routeId, String fallbackAction,
                                                boolean cacheEnabled) {
        return new RouteEvaluationRequest(
                routeId, "test-route", "test-tenant",
                new RouteEvaluationRequest.AiFilterConfig(
                        "Block SQL injection", "SYNC", false, 512,
                        fallbackAction, 0.85, cacheEnabled, 30),
                new RouteEvaluationRequest.RequestContext(
                        "POST", "/api/orders", null, "127.0.0.1",
                        Map.of("Content-Type", "application/json"), null, null)
        );
    }

    private RouteEvaluationRequest buildRequestWithPolicy(String routeId, String policy) {
        return new RouteEvaluationRequest(
                routeId, "test-route", "test-tenant",
                new RouteEvaluationRequest.AiFilterConfig(
                        policy, "SYNC", false, 512, "ALLOW", 0.85, true, 30),
                new RouteEvaluationRequest.RequestContext(
                        "GET", "/api/test", null, "127.0.0.1",
                        Map.of(), null, null)
        );
    }
}

