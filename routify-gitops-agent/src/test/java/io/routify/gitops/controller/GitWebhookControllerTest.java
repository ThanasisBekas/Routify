package io.routify.gitops.controller;

import io.routify.gitops.config.GitOpsProperties;
import io.routify.gitops.reconcile.ReconciliationResult;
import io.routify.gitops.reconcile.ReconciliationResult.ReconciliationOutcome;
import io.routify.gitops.reconcile.ReconciliationService;
import io.routify.gitops.reconcile.WebhookNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for {@link GitWebhookController} — webhook signature validation,
 * event type filtering, branch filtering, and manual sync.
 *
 * <p>Uses standalone MockMvc (no Spring Boot context needed).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GitWebhookController")
class GitWebhookControllerTest {

    @Mock
    private ReconciliationService reconciliationService;

    @Mock
    private GitOpsProperties properties;

    private MockMvc mockMvc;

    private final ReconciliationResult successResult = new ReconciliationResult(
            Instant.now(), "abc123", "hash456",
            ReconciliationOutcome.APPLIED,
            1, 0, 0, 0, List.of(), null);

    @BeforeEach
    void setUp() {
        GitWebhookController controller = new GitWebhookController(reconciliationService, properties);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("Valid push webhook triggers reconciliation and returns outcome")
    void validPushWebhook_triggersReconciliation() throws Exception {
        when(properties.getWebhookSecret()).thenReturn("");
        when(properties.getBranch()).thenReturn("main");
        when(reconciliationService.reconcile()).thenReturn(successResult);

        mockMvc.perform(post("/api/v1/gitops/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitHub-Event", "push")
                        .content("{\"ref\": \"refs/heads/main\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("triggered"))
                .andExpect(jsonPath("$.outcome").value("APPLIED"));

        verify(reconciliationService).reconcile();
    }

    @Test
    @DisplayName("Missing signature when secret configured returns 400")
    void missingSignature_whenSecretConfigured_returns400() throws Exception {
        when(properties.getWebhookSecret()).thenReturn("my-secret");

        mockMvc.perform(post("/api/v1/gitops/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ref\": \"refs/heads/main\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Missing X-Hub-Signature-256 header"));

        verifyNoInteractions(reconciliationService);
    }

    @Test
    @DisplayName("Invalid signature returns 403")
    void invalidSignature_returns403() throws Exception {
        when(properties.getWebhookSecret()).thenReturn("my-secret");

        mockMvc.perform(post("/api/v1/gitops/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", "sha256=invalid-signature")
                        .content("{\"ref\": \"refs/heads/main\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Invalid webhook signature"));

        verifyNoInteractions(reconciliationService);
    }

    @Test
    @DisplayName("Valid HMAC signature passes validation")
    void validSignature_accepted() throws Exception {
        String secret = "test-secret";
        String body = "{\"ref\": \"refs/heads/main\"}";
        String hmac = WebhookNotifier.computeHmacSha256(body, secret);

        when(properties.getWebhookSecret()).thenReturn(secret);
        when(properties.getBranch()).thenReturn("main");
        when(reconciliationService.reconcile()).thenReturn(successResult);

        mockMvc.perform(post("/api/v1/gitops/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", "sha256=" + hmac)
                        .header("X-GitHub-Event", "push")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("triggered"));

        verify(reconciliationService).reconcile();
    }

    @Test
    @DisplayName("Non-push GitHub event is ignored")
    void nonPushGitHubEvent_ignored() throws Exception {
        when(properties.getWebhookSecret()).thenReturn("");

        mockMvc.perform(post("/api/v1/gitops/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitHub-Event", "pull_request")
                        .content("{\"action\": \"opened\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ignored"))
                .andExpect(jsonPath("$.reason").value("Not a push event"));

        verifyNoInteractions(reconciliationService);
    }

    @Test
    @DisplayName("Non-push GitLab event is ignored")
    void nonPushGitLabEvent_ignored() throws Exception {
        when(properties.getWebhookSecret()).thenReturn("");

        mockMvc.perform(post("/api/v1/gitops/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitLab-Event", "Merge Request Hook")
                        .content("{\"event_type\": \"merge_request\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ignored"))
                .andExpect(jsonPath("$.reason").value("Not a push event"));

        verifyNoInteractions(reconciliationService);
    }

    @Test
    @DisplayName("Push for wrong branch is ignored")
    void pushWrongBranch_ignored() throws Exception {
        when(properties.getWebhookSecret()).thenReturn("");
        when(properties.getBranch()).thenReturn("main");

        mockMvc.perform(post("/api/v1/gitops/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitHub-Event", "push")
                        .content("{\"ref\": \"refs/heads/develop\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ignored"))
                .andExpect(jsonPath("$.reason").value("Push not for branch main"));

        verifyNoInteractions(reconciliationService);
    }

    @Test
    @DisplayName("Manual /sync endpoint triggers reconciliation")
    void manualSync_triggersReconciliation() throws Exception {
        when(reconciliationService.reconcile()).thenReturn(successResult);

        mockMvc.perform(post("/api/v1/gitops/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("triggered"))
                .andExpect(jsonPath("$.outcome").value("APPLIED"));

        verify(reconciliationService).reconcile();
    }

    @Test
    @DisplayName("GitLab push event triggers reconciliation")
    void gitLabPushEvent_triggersReconciliation() throws Exception {
        when(properties.getWebhookSecret()).thenReturn("");
        when(properties.getBranch()).thenReturn("main");
        when(reconciliationService.reconcile()).thenReturn(successResult);

        mockMvc.perform(post("/api/v1/gitops/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitLab-Event", "Push Hook")
                        .content("{\"ref\": \"refs/heads/main\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("triggered"));

        verify(reconciliationService).reconcile();
    }
}

