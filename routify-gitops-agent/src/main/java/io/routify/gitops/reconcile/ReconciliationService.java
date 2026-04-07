package io.routify.gitops.reconcile;

import io.routify.gitops.config.GitOpsProperties;
import io.routify.gitops.git.GitRepositoryClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Core reconciliation engine that orchestrates the GitOps sync loop:
 *
 * <ol>
 *   <li>Fetch latest from Git repository</li>
 *   <li>Read config YAML and compute SHA-256 hash</li>
 *   <li>Compare with last-applied hash (stored in Redis)</li>
 *   <li>If changed: preview → validate → apply (or drift-detect in dry-run mode)</li>
 *   <li>Update last-applied hash in Redis on success</li>
 *   <li>Fire webhook notification with result</li>
 *   <li>Store result in reconciliation history (Redis list, last 50)</li>
 * </ol>
 */
@Service
@Slf4j
public class ReconciliationService {

    private static final String REDIS_HASH_KEY_PREFIX = "routify:gitops:last-hash:";
    private static final String REDIS_HISTORY_KEY_PREFIX = "routify:gitops:history:";
    private static final int MAX_HISTORY_SIZE = 50;

    private final GitOpsProperties properties;
    private final GitRepositoryClient gitClient;
    private final AdminApiClient adminApiClient;
    private final WebhookNotifier webhookNotifier;
    private final StringRedisTemplate redisTemplate;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    // Metrics
    private final Counter reconciliationsApplied;
    private final Counter reconciliationsFailed;
    private final Counter reconciliationsDriftDetected;
    private final Counter reconciliationsNoChange;
    private final Timer reconciliationLatency;

    // Guard against concurrent reconciliation
    private final AtomicBoolean reconciling = new AtomicBoolean(false);

    public ReconciliationService(
            GitOpsProperties properties,
            GitRepositoryClient gitClient,
            AdminApiClient adminApiClient,
            WebhookNotifier webhookNotifier,
            StringRedisTemplate redisTemplate,
            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.gitClient = gitClient;
        this.adminApiClient = adminApiClient;
        this.webhookNotifier = webhookNotifier;
        this.redisTemplate = redisTemplate;
        this.objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        objectMapper.findAndRegisterModules();

        // Register Micrometer metrics under routify.gitops.* namespace
        this.reconciliationsApplied = Counter.builder("routify.gitops.reconciliations")
                .tag("outcome", "APPLIED")
                .description("Number of successful reconciliation applies")
                .register(meterRegistry);
        this.reconciliationsFailed = Counter.builder("routify.gitops.reconciliations")
                .tag("outcome", "FAILED")
                .description("Number of failed reconciliations")
                .register(meterRegistry);
        this.reconciliationsDriftDetected = Counter.builder("routify.gitops.reconciliations")
                .tag("outcome", "DRIFT_DETECTED")
                .description("Number of drift detections (dry-run)")
                .register(meterRegistry);
        this.reconciliationsNoChange = Counter.builder("routify.gitops.reconciliations")
                .tag("outcome", "NO_CHANGE")
                .description("Number of no-change reconciliation cycles")
                .register(meterRegistry);
        this.reconciliationLatency = Timer.builder("routify.gitops.latency")
                .description("Reconciliation cycle duration")
                .register(meterRegistry);
    }

    /**
     * Scheduled poll — runs on the configured interval.
     * Spring's {@code @Scheduled} with {@code fixedDelayString} uses
     * the property value in milliseconds.
     */
    @Scheduled(fixedDelayString = "#{${routify.gitops.poll-interval-seconds:60} * 1000}",
            initialDelayString = "5000")
    public void scheduledReconcile() {
        if (!properties.isEnabled()) return;
        reconcile();
    }

    /**
     * Performs a single reconciliation cycle. Thread-safe — concurrent invocations
     * are rejected (e.g. poll + webhook trigger overlapping).
     *
     * @return the reconciliation result
     */
    public ReconciliationResult reconcile() {
        if (!reconciling.compareAndSet(false, true)) {
            log.debug("Reconciliation already in progress — skipping");
            return new ReconciliationResult(
                    Instant.now(), null, null,
                    ReconciliationResult.ReconciliationOutcome.NO_CHANGE,
                    0, 0, 0, 0, List.of("Reconciliation already in progress"), null);
        }

        long start = System.nanoTime();
        try {
            return doReconcile();
        } finally {
            long elapsed = System.nanoTime() - start;
            reconciliationLatency.record(elapsed, TimeUnit.NANOSECONDS);
            reconciling.set(false);
        }
    }

    /**
     * Returns the last-applied config hash from Redis.
     */
    public Optional<String> getLastAppliedHash() {
        String key = REDIS_HASH_KEY_PREFIX + properties.getTenantId();
        return Optional.ofNullable(redisTemplate.opsForValue().get(key));
    }

    /**
     * Returns the last N reconciliation results from Redis.
     */
    @SuppressWarnings("unchecked")
    public List<ReconciliationResult> getHistory() {
        String key = REDIS_HISTORY_KEY_PREFIX + properties.getTenantId();
        List<String> raw = redisTemplate.opsForList().range(key, 0, MAX_HISTORY_SIZE - 1);
        if (raw == null || raw.isEmpty()) return List.of();

        List<ReconciliationResult> results = new ArrayList<>();
        for (String json : raw) {
            try {
                results.add(objectMapper.readValue(json, ReconciliationResult.class));
            } catch (Exception e) {
                log.warn("Failed to deserialize history entry: {}", e.getMessage());
            }
        }
        return results;
    }

    @SuppressWarnings("unchecked")
    private ReconciliationResult doReconcile() {
        log.info("Starting reconciliation cycle");

        // 1. Fetch latest from Git
        try {
            gitClient.fetchLatest();
        } catch (Exception e) {
            log.error("Git fetch failed: {}", e.getMessage(), e);
            var result = failedResult(null, null, "Git fetch failed: " + e.getMessage());
            recordResult(result);
            return result;
        }

        // 2. Read config file
        Optional<String> configContent = gitClient.readConfigFile();
        if (configContent.isEmpty()) {
            var result = failedResult(
                    gitClient.getHeadCommitHash().orElse(null), null,
                    "Config file not found: " + properties.getConfigPath());
            recordResult(result);
            return result;
        }

        String yaml = configContent.get();
        String configHash = GitRepositoryClient.computeSha256(yaml);
        String commitHash = gitClient.getHeadCommitHash().orElse("unknown");

        // 3. Compare with last-applied hash (SHA-256 short-circuit)
        Optional<String> lastHash = getLastAppliedHash();
        if (lastHash.isPresent() && lastHash.get().equals(configHash)) {
            log.info("Config hash unchanged ({}) — no reconciliation needed", configHash.substring(0, 12));
            var result = new ReconciliationResult(
                    Instant.now(), commitHash, configHash,
                    ReconciliationResult.ReconciliationOutcome.NO_CHANGE,
                    0, 0, 0, 0, List.of(), null);
            reconciliationsNoChange.increment();
            // Don't store NO_CHANGE in history to avoid noise
            return result;
        }

        // 4. Call import preview
        log.info("Config hash changed (old={}, new={}) — running import preview",
                lastHash.orElse("none"), configHash.substring(0, 12));

        Optional<Map<String, Object>> previewResult = adminApiClient.previewImport(yaml);
        if (previewResult.isEmpty()) {
            var result = failedResult(commitHash, configHash, "Import preview call failed");
            recordResult(result);
            return result;
        }

        Map<String, Object> preview = previewResult.get();

        // Check if preview indicates validation failure
        Boolean valid = (Boolean) preview.getOrDefault("valid", true);
        if (Boolean.FALSE.equals(valid)) {
            String error = String.valueOf(preview.getOrDefault("error", "Validation failed"));
            log.error("Import preview validation failed: {}", error);
            var result = failedResult(commitHash, configHash, error);
            recordResult(result);
            return result;
        }

        // Extract diff counts from preview
        int routesCreated = toInt(preview.getOrDefault("routesToCreate", 0));
        int routesUpdated = toInt(preview.getOrDefault("routesToUpdate", 0));
        int filtersCreated = toInt(preview.getOrDefault("filtersToCreate", 0));
        int filtersUpdated = toInt(preview.getOrDefault("filtersToUpdate", 0));
        List<String> warnings = preview.containsKey("warnings")
                ? (List<String>) preview.get("warnings")
                : List.of();

        boolean hasChanges = routesCreated + routesUpdated + filtersCreated + filtersUpdated > 0;

        // 5. If no changes in preview (all unchanged), update hash and skip
        if (!hasChanges) {
            log.info("Import preview shows no changes — updating hash");
            updateLastAppliedHash(configHash);
            var result = new ReconciliationResult(
                    Instant.now(), commitHash, configHash,
                    ReconciliationResult.ReconciliationOutcome.NO_CHANGE,
                    0, 0, 0, 0, warnings, null);
            reconciliationsNoChange.increment();
            return result;
        }

        // 6. Dry-run mode — detect drift without applying
        if (properties.isDryRun()) {
            log.info("Dry-run mode — drift detected: {} routes to create, {} to update, " +
                            "{} filters to create, {} to update",
                    routesCreated, routesUpdated, filtersCreated, filtersUpdated);

            var result = new ReconciliationResult(
                    Instant.now(), commitHash, configHash,
                    ReconciliationResult.ReconciliationOutcome.DRIFT_DETECTED,
                    routesCreated, routesUpdated, filtersCreated, filtersUpdated,
                    warnings, null);

            reconciliationsDriftDetected.increment();
            recordResult(result);
            webhookNotifier.notify(result);
            return result;
        }

        // 7. Apply changes
        log.info("Applying import: {} routes to create, {} to update, {} filters to create, {} to update",
                routesCreated, routesUpdated, filtersCreated, filtersUpdated);

        boolean applied = adminApiClient.applyImport(yaml);
        if (!applied) {
            var result = failedResult(commitHash, configHash, "Import apply call failed");
            recordResult(result);
            return result;
        }

        // 8. Success — update hash in Redis
        updateLastAppliedHash(configHash);

        var result = new ReconciliationResult(
                Instant.now(), commitHash, configHash,
                ReconciliationResult.ReconciliationOutcome.APPLIED,
                routesCreated, routesUpdated, filtersCreated, filtersUpdated,
                warnings, null);

        reconciliationsApplied.increment();
        recordResult(result);
        webhookNotifier.notify(result);

        log.info("Reconciliation completed successfully — applied commit {}", commitHash);
        return result;
    }

    private void updateLastAppliedHash(String hash) {
        String key = REDIS_HASH_KEY_PREFIX + properties.getTenantId();
        redisTemplate.opsForValue().set(key, hash);
    }

    private void recordResult(ReconciliationResult result) {
        try {
            String key = REDIS_HISTORY_KEY_PREFIX + properties.getTenantId();
            String json = objectMapper.writeValueAsString(result);
            redisTemplate.opsForList().leftPush(key, json);
            redisTemplate.opsForList().trim(key, 0, MAX_HISTORY_SIZE - 1);
        } catch (Exception e) {
            log.warn("Failed to store reconciliation result in Redis: {}", e.getMessage());
        }

        // Also fire webhook for failures
        if (result.outcome() == ReconciliationResult.ReconciliationOutcome.FAILED) {
            reconciliationsFailed.increment();
            webhookNotifier.notify(result);
        }
    }

    private ReconciliationResult failedResult(String commitHash, String configHash, String error) {
        return new ReconciliationResult(
                Instant.now(), commitHash, configHash,
                ReconciliationResult.ReconciliationOutcome.FAILED,
                0, 0, 0, 0, List.of(), error);
    }

    private static int toInt(Object value) {
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

