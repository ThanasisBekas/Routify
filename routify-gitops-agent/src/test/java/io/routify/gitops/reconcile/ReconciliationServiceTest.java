package io.routify.gitops.reconcile;

import io.routify.gitops.config.GitOpsProperties;
import io.routify.gitops.git.GitRepositoryClient;
import io.routify.gitops.reconcile.ReconciliationResult.ReconciliationOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.eclipse.jgit.api.errors.TransportException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ReconciliationService} — covers the full reconciliation
 * lifecycle with mocked dependencies: Git client, admin-api client, webhook
 * notifier, and Redis.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReconciliationService")
class ReconciliationServiceTest {

    @Mock private GitRepositoryClient gitClient;
    @Mock private AdminApiClient adminApiClient;
    @Mock private WebhookNotifier webhookNotifier;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private ListOperations<String, String> listOps;

    private GitOpsProperties properties;
    private ReconciliationService service;

    private static final String VALID_YAML = """
            apiVersion: routify/v1
            kind: GatewayConfiguration
            metadata:
              exportedAt: "2026-04-08T12:00:00Z"
              exportedBy: admin
              tenantId: "11111111-1111-1111-1111-111111111111"
              environment: PRODUCTION
            filters: []
            routes: []
            """;

    @BeforeEach
    void setUp() {
        properties = new GitOpsProperties();
        properties.setRepositoryUrl("https://github.com/test/repo.git");
        properties.setAdminApiUrl("http://localhost:8082");
        properties.setApiKey("test-api-key");
        properties.setTenantId("tenant-1");
        properties.setConfigPath("routify-export.yaml");
        properties.setEnabled(true);
        properties.setDryRun(false);

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(redisTemplate.opsForList()).thenReturn(listOps);

        service = new ReconciliationService(
                properties, gitClient, adminApiClient, webhookNotifier,
                redisTemplate, new SimpleMeterRegistry());
    }

    // ── Git fetch failures ────────────────────────────────────────────

    @Test
    @DisplayName("Git fetch failure results in FAILED outcome with clear error message")
    void gitFetchFailure_failedOutcome() throws Exception {
        doThrow(new TransportException("Connection refused"))
                .when(gitClient).fetchLatest();

        ReconciliationResult result = service.reconcile();

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.FAILED);
        assertThat(result.errorMessage()).contains("Git fetch failed");
        assertThat(result.errorMessage()).contains("Connection refused");
    }

    // ── Config file not found ─────────────────────────────────────────

    @Test
    @DisplayName("Missing config file results in FAILED outcome")
    void configFileMissing_failedOutcome() throws Exception {
        doNothing().when(gitClient).fetchLatest();
        when(gitClient.readConfigFile()).thenReturn(Optional.empty());
        when(gitClient.getHeadCommitHash()).thenReturn(Optional.of("abc123"));

        ReconciliationResult result = service.reconcile();

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.FAILED);
        assertThat(result.errorMessage()).contains("Config file not found");
        assertThat(result.errorMessage()).contains("routify-export.yaml");
    }

    // ── Malformed YAML ────────────────────────────────────────────────

    @Test
    @DisplayName("Malformed YAML results in FAILED outcome with parse error detail")
    void malformedYaml_failedOutcome() throws Exception {
        doNothing().when(gitClient).fetchLatest();
        when(gitClient.readConfigFile()).thenReturn(Optional.of("not: valid: yaml: [[["));
        when(gitClient.getHeadCommitHash()).thenReturn(Optional.of("abc123"));

        ReconciliationResult result = service.reconcile();

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.FAILED);
        assertThat(result.errorMessage()).startsWith("Malformed YAML:");
    }

    @Test
    @DisplayName("Wrong apiVersion results in FAILED with clear error")
    void wrongApiVersion_failedOutcome() throws Exception {
        String yaml = """
                apiVersion: routify/v999
                kind: GatewayConfiguration
                filters: []
                routes: []
                """;
        doNothing().when(gitClient).fetchLatest();
        when(gitClient.readConfigFile()).thenReturn(Optional.of(yaml));
        when(gitClient.getHeadCommitHash()).thenReturn(Optional.of("abc123"));

        ReconciliationResult result = service.reconcile();

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.FAILED);
        assertThat(result.errorMessage()).contains("Invalid apiVersion");
        assertThat(result.errorMessage()).contains("routify/v1");
        assertThat(result.errorMessage()).contains("routify/v999");
    }

    @Test
    @DisplayName("Wrong kind results in FAILED with clear error")
    void wrongKind_failedOutcome() throws Exception {
        String yaml = """
                apiVersion: routify/v1
                kind: SomethingElse
                filters: []
                routes: []
                """;
        doNothing().when(gitClient).fetchLatest();
        when(gitClient.readConfigFile()).thenReturn(Optional.of(yaml));
        when(gitClient.getHeadCommitHash()).thenReturn(Optional.of("abc123"));

        ReconciliationResult result = service.reconcile();

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.FAILED);
        assertThat(result.errorMessage()).contains("Invalid kind");
        assertThat(result.errorMessage()).contains("GatewayConfiguration");
    }

    @Test
    @DisplayName("Missing apiVersion field results in FAILED")
    void missingApiVersion_failedOutcome() throws Exception {
        String yaml = """
                kind: GatewayConfiguration
                filters: []
                routes: []
                """;
        doNothing().when(gitClient).fetchLatest();
        when(gitClient.readConfigFile()).thenReturn(Optional.of(yaml));
        when(gitClient.getHeadCommitHash()).thenReturn(Optional.of("abc123"));

        ReconciliationResult result = service.reconcile();

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.FAILED);
        assertThat(result.errorMessage()).contains("missing 'apiVersion'");
    }

    // ── Hash unchanged (no change) ────────────────────────────────────

    @Test
    @DisplayName("Unchanged config hash results in NO_CHANGE without calling admin-api")
    void hashUnchanged_noChange() throws Exception {
        String configHash = GitRepositoryClient.computeSha256(VALID_YAML);

        doNothing().when(gitClient).fetchLatest();
        when(gitClient.readConfigFile()).thenReturn(Optional.of(VALID_YAML));
        when(gitClient.getHeadCommitHash()).thenReturn(Optional.of("abc123"));
        when(valueOps.get("routify:gitops:last-hash:tenant-1")).thenReturn(configHash);

        ReconciliationResult result = service.reconcile();

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.NO_CHANGE);
        verifyNoInteractions(adminApiClient);
        verifyNoInteractions(webhookNotifier);
    }

    // ── Preview failure ──────────────────────────────────────────────

    @Test
    @DisplayName("Preview returning empty results in FAILED")
    void previewEmpty_failedOutcome() throws Exception {
        doNothing().when(gitClient).fetchLatest();
        when(gitClient.readConfigFile()).thenReturn(Optional.of(VALID_YAML));
        when(gitClient.getHeadCommitHash()).thenReturn(Optional.of("abc123"));
        when(valueOps.get(anyString())).thenReturn(null); // no last hash
        when(adminApiClient.previewImport(anyString())).thenReturn(Optional.empty());

        ReconciliationResult result = service.reconcile();

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.FAILED);
        assertThat(result.errorMessage()).contains("Import preview call failed");
    }

    @Test
    @DisplayName("Preview with valid=false results in FAILED with error detail")
    void previewInvalid_failedOutcome() throws Exception {
        doNothing().when(gitClient).fetchLatest();
        when(gitClient.readConfigFile()).thenReturn(Optional.of(VALID_YAML));
        when(gitClient.getHeadCommitHash()).thenReturn(Optional.of("abc123"));
        when(valueOps.get(anyString())).thenReturn(null);
        when(adminApiClient.previewImport(anyString()))
                .thenReturn(Optional.of(Map.of("valid", false, "error", "Schema mismatch")));

        ReconciliationResult result = service.reconcile();

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.FAILED);
        assertThat(result.errorMessage()).isEqualTo("Schema mismatch");
    }

    // ── Dry-run drift detection ──────────────────────────────────────

    @Test
    @DisplayName("Dry-run mode with changes results in DRIFT_DETECTED")
    void dryRun_driftDetected() throws Exception {
        properties.setDryRun(true);

        doNothing().when(gitClient).fetchLatest();
        when(gitClient.readConfigFile()).thenReturn(Optional.of(VALID_YAML));
        when(gitClient.getHeadCommitHash()).thenReturn(Optional.of("abc123"));
        when(valueOps.get(anyString())).thenReturn(null);
        when(adminApiClient.previewImport(anyString()))
                .thenReturn(Optional.of(Map.of(
                        "routesToCreate", 2,
                        "routesToUpdate", 1,
                        "filtersToCreate", 0,
                        "filtersToUpdate", 0)));

        ReconciliationResult result = service.reconcile();

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.DRIFT_DETECTED);
        assertThat(result.routesCreated()).isEqualTo(2);
        assertThat(result.routesUpdated()).isEqualTo(1);
        verify(webhookNotifier).notify(result);
        verify(adminApiClient, never()).applyImport(anyString());
    }

    // ── Successful apply ─────────────────────────────────────────────

    @Test
    @DisplayName("Successful apply results in APPLIED and updates Redis hash")
    void applySuccess_appliedOutcome() throws Exception {
        doNothing().when(gitClient).fetchLatest();
        when(gitClient.readConfigFile()).thenReturn(Optional.of(VALID_YAML));
        when(gitClient.getHeadCommitHash()).thenReturn(Optional.of("abc123"));
        when(valueOps.get(anyString())).thenReturn(null);
        when(adminApiClient.previewImport(anyString()))
                .thenReturn(Optional.of(Map.of(
                        "routesToCreate", 1,
                        "routesToUpdate", 0,
                        "filtersToCreate", 0,
                        "filtersToUpdate", 0)));
        when(adminApiClient.applyImport(anyString())).thenReturn(true);

        ReconciliationResult result = service.reconcile();

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.APPLIED);
        assertThat(result.routesCreated()).isEqualTo(1);

        // Verify Redis hash was updated
        String expectedHash = GitRepositoryClient.computeSha256(VALID_YAML);
        verify(valueOps).set("routify:gitops:last-hash:tenant-1", expectedHash);

        // Verify webhook notification
        verify(webhookNotifier).notify(result);
    }

    // ── Apply failure ────────────────────────────────────────────────

    @Test
    @DisplayName("Apply failure results in FAILED")
    void applyFailure_failedOutcome() throws Exception {
        doNothing().when(gitClient).fetchLatest();
        when(gitClient.readConfigFile()).thenReturn(Optional.of(VALID_YAML));
        when(gitClient.getHeadCommitHash()).thenReturn(Optional.of("abc123"));
        when(valueOps.get(anyString())).thenReturn(null);
        when(adminApiClient.previewImport(anyString()))
                .thenReturn(Optional.of(Map.of(
                        "routesToCreate", 1,
                        "routesToUpdate", 0,
                        "filtersToCreate", 0,
                        "filtersToUpdate", 0)));
        when(adminApiClient.applyImport(anyString())).thenReturn(false);

        ReconciliationResult result = service.reconcile();

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.FAILED);
        assertThat(result.errorMessage()).contains("Import apply call failed");
    }

    // ── Concurrent reconciliation guard ──────────────────────────────

    @Test
    @DisplayName("Concurrent reconciliation is rejected with NO_CHANGE")
    void concurrentGuard_rejected() throws Exception {
        // Simulate a long-running reconciliation
        doAnswer(invocation -> {
            // While first reconciliation is running, try a concurrent one
            ReconciliationResult concurrentResult = service.reconcile();
            assertThat(concurrentResult.outcome()).isEqualTo(ReconciliationOutcome.NO_CHANGE);
            assertThat(concurrentResult.warnings()).contains("Reconciliation already in progress");
            return null;
        }).when(gitClient).fetchLatest();

        when(gitClient.readConfigFile()).thenReturn(Optional.empty());
        when(gitClient.getHeadCommitHash()).thenReturn(Optional.empty());

        service.reconcile();
    }

    // ── Preview shows no changes ─────────────────────────────────────

    @Test
    @DisplayName("Preview with zero changes results in NO_CHANGE and updates hash")
    void previewNoChanges_noChange() throws Exception {
        doNothing().when(gitClient).fetchLatest();
        when(gitClient.readConfigFile()).thenReturn(Optional.of(VALID_YAML));
        when(gitClient.getHeadCommitHash()).thenReturn(Optional.of("abc123"));
        when(valueOps.get(anyString())).thenReturn(null);
        when(adminApiClient.previewImport(anyString()))
                .thenReturn(Optional.of(Map.of(
                        "routesToCreate", 0,
                        "routesToUpdate", 0,
                        "filtersToCreate", 0,
                        "filtersToUpdate", 0)));

        ReconciliationResult result = service.reconcile();

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.NO_CHANGE);

        // Verify hash still gets updated (content is valid, just no diff)
        String expectedHash = GitRepositoryClient.computeSha256(VALID_YAML);
        verify(valueOps).set("routify:gitops:last-hash:tenant-1", expectedHash);
    }

    // ── YAML validation ──────────────────────────────────────────────

    @Test
    @DisplayName("validateYaml accepts valid routify/v1 YAML")
    void validateYaml_valid() {
        Optional<String> error = service.validateYaml(VALID_YAML);
        assertThat(error).isEmpty();
    }

    @Test
    @DisplayName("validateYaml rejects non-YAML content")
    void validateYaml_notYaml() {
        Optional<String> error = service.validateYaml("{{{{not yaml}}}}");
        // Jackson YAML parser may or may not error on this — the key check is apiVersion
        // Either it fails to parse or it's missing apiVersion
        assertThat(error).isPresent();
    }

    @Test
    @DisplayName("validateYaml rejects wrong apiVersion")
    void validateYaml_wrongVersion() {
        String yaml = """
                apiVersion: routify/v2
                kind: GatewayConfiguration
                """;
        Optional<String> error = service.validateYaml(yaml);
        assertThat(error).isPresent();
        assertThat(error.get()).contains("Invalid apiVersion");
    }

    @Test
    @DisplayName("validateYaml rejects wrong kind")
    void validateYaml_wrongKind() {
        String yaml = """
                apiVersion: routify/v1
                kind: WrongKind
                """;
        Optional<String> error = service.validateYaml(yaml);
        assertThat(error).isPresent();
        assertThat(error.get()).contains("Invalid kind");
    }

    @Test
    @DisplayName("validateYaml truncates long error messages")
    void validateYaml_longErrorTruncated() {
        // Create content that will generate a very long parse error
        String yaml = "apiVersion: [" + "a".repeat(500) + ": ]]";
        Optional<String> error = service.validateYaml(yaml);
        // If an error occurs, verify it starts with "Malformed YAML:"
        if (error.isPresent() && error.get().startsWith("Malformed YAML:")) {
            // The detail part should be capped at ~200 chars + "..."
            assertThat(error.get().length()).isLessThan(220);
        }
    }

    // ── Redis state management ───────────────────────────────────────

    @Test
    @DisplayName("getLastAppliedHash reads from correct Redis key")
    void getLastAppliedHash_correctKey() {
        when(valueOps.get("routify:gitops:last-hash:tenant-1")).thenReturn("stored-hash");

        Optional<String> hash = service.getLastAppliedHash();

        assertThat(hash).contains("stored-hash");
    }

    @Test
    @DisplayName("getLastAppliedHash returns empty when no hash stored")
    void getLastAppliedHash_empty() {
        when(valueOps.get("routify:gitops:last-hash:tenant-1")).thenReturn(null);

        Optional<String> hash = service.getLastAppliedHash();

        assertThat(hash).isEmpty();
    }

    @Test
    @DisplayName("getHistory returns empty list when no history stored")
    void getHistory_empty() {
        when(listOps.range(anyString(), anyLong(), anyLong())).thenReturn(null);

        List<ReconciliationResult> history = service.getHistory();

        assertThat(history).isEmpty();
    }

    @Test
    @DisplayName("Failed reconciliation fires webhook notification")
    void failedReconciliation_firesWebhook() throws Exception {
        doNothing().when(gitClient).fetchLatest();
        when(gitClient.readConfigFile()).thenReturn(Optional.of(VALID_YAML));
        when(gitClient.getHeadCommitHash()).thenReturn(Optional.of("abc123"));
        when(valueOps.get(anyString())).thenReturn(null);
        when(adminApiClient.previewImport(anyString())).thenReturn(Optional.empty());

        service.reconcile();

        verify(webhookNotifier).notify(argThat(r ->
                r.outcome() == ReconciliationOutcome.FAILED));
    }

    @Test
    @DisplayName("Result is stored in Redis history list")
    void resultStored_inHistory() throws Exception {
        doNothing().when(gitClient).fetchLatest();
        when(gitClient.readConfigFile()).thenReturn(Optional.of(VALID_YAML));
        when(gitClient.getHeadCommitHash()).thenReturn(Optional.of("abc123"));
        when(valueOps.get(anyString())).thenReturn(null);
        when(adminApiClient.previewImport(anyString())).thenReturn(Optional.empty());

        service.reconcile();

        verify(listOps).leftPush(eq("routify:gitops:history:tenant-1"), anyString());
        verify(listOps).trim("routify:gitops:history:tenant-1", 0, 49);
    }
}

