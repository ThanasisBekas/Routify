package io.routify.gitops.reconcile;

import java.time.Instant;
import java.util.List;

/**
 * Immutable record representing the outcome of a single reconciliation cycle.
 *
 * @param timestamp        when the reconciliation completed
 * @param commitHash       Git commit hash at HEAD during this cycle
 * @param configHash       SHA-256 hash of the configuration YAML file
 * @param outcome          reconciliation outcome
 * @param routesCreated    number of routes that would be or were created
 * @param routesUpdated    number of routes that would be or were updated
 * @param filtersCreated   number of filters that would be or were created
 * @param filtersUpdated   number of filters that would be or were updated
 * @param warnings         any non-fatal warnings from the import preview
 * @param errorMessage     error message if outcome is FAILED, null otherwise
 */
public record ReconciliationResult(
        Instant timestamp,
        String commitHash,
        String configHash,
        ReconciliationOutcome outcome,
        int routesCreated,
        int routesUpdated,
        int filtersCreated,
        int filtersUpdated,
        List<String> warnings,
        String errorMessage
) {

    /**
     * Possible outcomes of a reconciliation cycle.
     */
    public enum ReconciliationOutcome {
        /** Changes were applied successfully. */
        APPLIED,
        /** Dry-run mode detected drift without applying. */
        DRIFT_DETECTED,
        /** Reconciliation failed (validation error, network error, etc.). */
        FAILED,
        /** No changes detected — config hash matches last-applied. */
        NO_CHANGE
    }
}

