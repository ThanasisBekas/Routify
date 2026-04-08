package io.routify.gitops.controller;

import io.routify.gitops.config.GitOpsProperties;
import io.routify.gitops.git.GitRepositoryClient;
import io.routify.gitops.reconcile.ReconciliationResult;
import io.routify.gitops.reconcile.ReconciliationResult.ReconciliationOutcome;
import io.routify.gitops.reconcile.ReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for {@link GitOpsStatusController} — status and history endpoints.
 *
 * <p>Uses standalone MockMvc (no Spring Boot context needed).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GitOpsStatusController")
class GitOpsStatusControllerTest {

    @Mock
    private ReconciliationService reconciliationService;

    @Mock
    private GitOpsProperties properties;

    @Mock
    private GitRepositoryClient gitClient;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        GitOpsStatusController controller = new GitOpsStatusController(
                reconciliationService, properties, gitClient);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("GET /status returns all expected fields")
    void getStatus_returnsAllFields() throws Exception {
        when(properties.isEnabled()).thenReturn(true);
        when(properties.getRepositoryUrl()).thenReturn("https://github.com/org/repo.git");
        when(properties.getBranch()).thenReturn("main");
        when(properties.getConfigPath()).thenReturn("routify-export.yaml");
        when(properties.getPollIntervalSeconds()).thenReturn(60);
        when(properties.isDryRun()).thenReturn(false);
        when(properties.getTenantId()).thenReturn("tenant-1");

        when(reconciliationService.getLastAppliedHash()).thenReturn(Optional.of("abc123hash"));
        when(gitClient.getHeadCommitHash()).thenReturn(Optional.of("def456commit"));

        var result = new ReconciliationResult(
                Instant.parse("2026-04-08T12:00:00Z"), "def456commit", "abc123hash",
                ReconciliationOutcome.APPLIED,
                1, 0, 0, 0, List.of(), null);
        when(reconciliationService.getHistory()).thenReturn(List.of(result));

        mockMvc.perform(get("/api/v1/gitops/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.repositoryUrl").value("https://github.com/org/repo.git"))
                .andExpect(jsonPath("$.branch").value("main"))
                .andExpect(jsonPath("$.configPath").value("routify-export.yaml"))
                .andExpect(jsonPath("$.pollIntervalSeconds").value(60))
                .andExpect(jsonPath("$.dryRun").value(false))
                .andExpect(jsonPath("$.tenantId").value("tenant-1"))
                .andExpect(jsonPath("$.lastAppliedHash").value("abc123hash"))
                .andExpect(jsonPath("$.lastCommitHash").value("def456commit"))
                .andExpect(jsonPath("$.lastOutcome").value("APPLIED"));
    }

    @Test
    @DisplayName("GET /status with no history returns null for lastSyncTime and lastOutcome")
    void getStatus_noHistory_nullFields() throws Exception {
        when(properties.isEnabled()).thenReturn(true);
        when(properties.getRepositoryUrl()).thenReturn("https://github.com/org/repo.git");
        when(properties.getBranch()).thenReturn("main");
        when(properties.getConfigPath()).thenReturn("routify-export.yaml");
        when(properties.getPollIntervalSeconds()).thenReturn(60);
        when(properties.isDryRun()).thenReturn(false);
        when(properties.getTenantId()).thenReturn("tenant-1");

        when(reconciliationService.getLastAppliedHash()).thenReturn(Optional.empty());
        when(gitClient.getHeadCommitHash()).thenReturn(Optional.empty());
        when(reconciliationService.getHistory()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/gitops/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastAppliedHash").isEmpty())
                .andExpect(jsonPath("$.lastCommitHash").isEmpty())
                .andExpect(jsonPath("$.lastSyncTime").isEmpty())
                .andExpect(jsonPath("$.lastOutcome").isEmpty());
    }

    @Test
    @DisplayName("GET /history returns list of reconciliation results")
    void getHistory_returnsList() throws Exception {
        var result1 = new ReconciliationResult(
                Instant.parse("2026-04-08T12:00:00Z"), "abc", "hash1",
                ReconciliationOutcome.APPLIED,
                1, 0, 0, 0, List.of(), null);
        var result2 = new ReconciliationResult(
                Instant.parse("2026-04-08T11:00:00Z"), "def", "hash2",
                ReconciliationOutcome.FAILED,
                0, 0, 0, 0, List.of(), "Connection refused");

        when(reconciliationService.getHistory()).thenReturn(List.of(result1, result2));

        mockMvc.perform(get("/api/v1/gitops/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].outcome").value("APPLIED"))
                .andExpect(jsonPath("$[1].outcome").value("FAILED"))
                .andExpect(jsonPath("$[1].errorMessage").value("Connection refused"));
    }

    @Test
    @DisplayName("GET /history returns empty array when no history")
    void getHistory_empty() throws Exception {
        when(reconciliationService.getHistory()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/gitops/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
