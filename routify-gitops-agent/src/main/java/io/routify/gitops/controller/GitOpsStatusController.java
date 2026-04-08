package io.routify.gitops.controller;

import io.routify.gitops.config.GitOpsProperties;
import io.routify.gitops.git.GitRepositoryClient;
import io.routify.gitops.reconcile.ReconciliationResult;
import io.routify.gitops.reconcile.ReconciliationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Status endpoints for the GitOps agent.
 *
 * <p>Exposes agent status and reconciliation history for the dashboard
 * and monitoring systems.
 */
@RestController
@RequestMapping("/api/v1/gitops")
@RequiredArgsConstructor
public class GitOpsStatusController {

    private final ReconciliationService reconciliationService;
    private final GitOpsProperties properties;
    private final GitRepositoryClient gitClient;

    /**
     * Returns the current agent status including configuration,
     * last sync information, and connection status.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", properties.isEnabled());
        status.put("repositoryUrl", properties.getRepositoryUrl());
        status.put("branch", properties.getBranch());
        status.put("configPath", properties.getConfigPath());
        status.put("pollIntervalSeconds", properties.getPollIntervalSeconds());
        status.put("dryRun", properties.isDryRun());
        status.put("tenantId", properties.getTenantId());

        // Last applied hash
        status.put("lastAppliedHash",
                reconciliationService.getLastAppliedHash().orElse(null));

        // Current HEAD commit
        status.put("lastCommitHash",
                gitClient.getHeadCommitHash().orElse(null));

        // Most recent reconciliation result
        List<ReconciliationResult> history = reconciliationService.getHistory();
        if (!history.isEmpty()) {
            ReconciliationResult latest = history.getFirst();
            status.put("lastSyncTime", latest.timestamp().toString());
            status.put("lastOutcome", latest.outcome().name());
        } else {
            status.put("lastSyncTime", null);
            status.put("lastOutcome", null);
        }

        return ResponseEntity.ok(status);
    }

    /**
     * Returns the reconciliation history (last 50 results).
     */
    @GetMapping("/history")
    public ResponseEntity<List<ReconciliationResult>> getHistory() {
        return ResponseEntity.ok(reconciliationService.getHistory());
    }
}

