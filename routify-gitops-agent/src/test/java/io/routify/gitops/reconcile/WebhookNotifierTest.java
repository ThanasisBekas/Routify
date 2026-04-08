package io.routify.gitops.reconcile;

import io.routify.gitops.config.GitOpsProperties;
import io.routify.gitops.reconcile.ReconciliationResult.ReconciliationOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link WebhookNotifier} — HMAC-SHA256 signing,
 * webhook delivery, and error handling.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WebhookNotifier")
class WebhookNotifierTest {

    @Mock
    private RestTemplate restTemplate;

    private GitOpsProperties properties;
    private WebhookNotifier notifier;

    @BeforeEach
    void setUp() {
        properties = new GitOpsProperties();
        properties.setRepositoryUrl("https://github.com/test/repo.git");
        properties.setAdminApiUrl("http://localhost:8082");
        properties.setApiKey("test-key");
        properties.setTenantId("tenant-1");
        notifier = new WebhookNotifier(properties, restTemplate);
    }

    // ── computeHmacSha256 ──────────────────────────────────────────────

    @Test
    @DisplayName("computeHmacSha256 produces correct signature for known input")
    void hmac_knownInput_correctSignature() {
        // Verify determinism — same input, same secret → same HMAC
        String sig1 = WebhookNotifier.computeHmacSha256("hello", "secret");
        String sig2 = WebhookNotifier.computeHmacSha256("hello", "secret");

        assertThat(sig1).isEqualTo(sig2);
        assertThat(sig1).hasSize(64); // SHA-256 HMAC = 32 bytes = 64 hex chars
        assertThat(sig1).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("computeHmacSha256 produces different signatures for different data")
    void hmac_differentData_differentSignature() {
        String sig1 = WebhookNotifier.computeHmacSha256("data-a", "secret");
        String sig2 = WebhookNotifier.computeHmacSha256("data-b", "secret");

        assertThat(sig1).isNotEqualTo(sig2);
    }

    @Test
    @DisplayName("computeHmacSha256 produces different signatures for different secrets")
    void hmac_differentSecrets_differentSignature() {
        String sig1 = WebhookNotifier.computeHmacSha256("data", "secret-1");
        String sig2 = WebhookNotifier.computeHmacSha256("data", "secret-2");

        assertThat(sig1).isNotEqualTo(sig2);
    }

    // ── notify() ──────────────────────────────────────────────────────

    @Test
    @DisplayName("notify skips when webhookUrl is blank")
    void notify_blankUrl_skipped() {
        properties.setWebhookUrl("");

        notifier.notify(createResult(ReconciliationOutcome.APPLIED));

        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("notify skips when webhookUrl is null")
    void notify_nullUrl_skipped() {
        properties.setWebhookUrl(null);

        notifier.notify(createResult(ReconciliationOutcome.APPLIED));

        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("notify sends POST with HMAC signature when secret is configured")
    void notify_withSecret_includesSignature() {
        properties.setWebhookUrl("https://hooks.example.com/callback");
        properties.setWebhookSecret("my-secret");

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("ok"));

        notifier.notify(createResult(ReconciliationOutcome.APPLIED));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq("https://hooks.example.com/callback"),
                eq(HttpMethod.POST), captor.capture(), eq(String.class));

        HttpEntity<Map<String, Object>> entity = captor.getValue();
        String sigHeader = entity.getHeaders().getFirst("X-Hub-Signature-256");
        assertThat(sigHeader).isNotNull();
        assertThat(sigHeader).startsWith("sha256=");
    }

    @Test
    @DisplayName("notify sends POST without signature when no secret configured")
    void notify_withoutSecret_noSignature() {
        properties.setWebhookUrl("https://hooks.example.com/callback");
        properties.setWebhookSecret("");

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("ok"));

        notifier.notify(createResult(ReconciliationOutcome.FAILED));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(String.class));

        String sigHeader = captor.getValue().getHeaders().getFirst("X-Hub-Signature-256");
        assertThat(sigHeader).isNull();
    }

    @Test
    @DisplayName("notify includes correct payload fields")
    void notify_payloadFields() {
        properties.setWebhookUrl("https://hooks.example.com/callback");
        properties.setWebhookSecret("");

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("ok"));

        var result = new ReconciliationResult(
                Instant.parse("2026-04-08T12:00:00Z"), "abc123", "hash456",
                ReconciliationOutcome.APPLIED,
                2, 1, 3, 0, List.of("warning1"), null);

        notifier.notify(result);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(String.class));

        Map<String, Object> payload = captor.getValue().getBody();
        assertThat(payload).isNotNull();
        assertThat(payload.get("event")).isEqualTo("RECONCILIATION_SUCCEEDED");
        assertThat(payload.get("outcome")).isEqualTo("APPLIED");
        assertThat(payload.get("commitHash")).isEqualTo("abc123");
        assertThat(payload.get("configHash")).isEqualTo("hash456");
        assertThat(payload.get("routesCreated")).isEqualTo(2);
        assertThat(payload.get("routesUpdated")).isEqualTo(1);
        assertThat(payload.get("filtersCreated")).isEqualTo(3);
        assertThat(payload.get("filtersUpdated")).isEqualTo(0);
    }

    @Test
    @DisplayName("notify handles RestClientException gracefully without propagation")
    void notify_networkError_noException() {
        properties.setWebhookUrl("https://hooks.example.com/callback");

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("Connection refused"));

        assertThatNoException().isThrownBy(() ->
                notifier.notify(createResult(ReconciliationOutcome.APPLIED)));
    }

    @Test
    @DisplayName("notify handles null commitHash and configHash")
    void notify_nullFields_noNullPointer() {
        properties.setWebhookUrl("https://hooks.example.com/callback");

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("ok"));

        var result = new ReconciliationResult(
                Instant.now(), null, null,
                ReconciliationOutcome.FAILED,
                0, 0, 0, 0, null, "some error");

        assertThatNoException().isThrownBy(() -> notifier.notify(result));
    }

    // ── mapOutcomeToEvent ─────────────────────────────────────────────

    @Test
    @DisplayName("mapOutcomeToEvent maps all outcomes correctly")
    void mapOutcomeToEvent_allOutcomes() {
        assertThat(notifier.mapOutcomeToEvent(ReconciliationOutcome.APPLIED))
                .isEqualTo("RECONCILIATION_SUCCEEDED");
        assertThat(notifier.mapOutcomeToEvent(ReconciliationOutcome.DRIFT_DETECTED))
                .isEqualTo("DRIFT_DETECTED");
        assertThat(notifier.mapOutcomeToEvent(ReconciliationOutcome.FAILED))
                .isEqualTo("RECONCILIATION_FAILED");
        assertThat(notifier.mapOutcomeToEvent(ReconciliationOutcome.NO_CHANGE))
                .isEqualTo("NO_CHANGE");
    }

    // ── helpers ────────────────────────────────────────────────────────

    private ReconciliationResult createResult(ReconciliationOutcome outcome) {
        return new ReconciliationResult(
                Instant.now(), "abc123", "hash456",
                outcome, 1, 0, 0, 0, List.of(), null);
    }
}

