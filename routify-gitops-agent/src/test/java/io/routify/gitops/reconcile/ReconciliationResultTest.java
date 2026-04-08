package io.routify.gitops.reconcile;

import io.routify.gitops.reconcile.ReconciliationResult.ReconciliationOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ReconciliationResult} record and {@link ReconciliationOutcome} enum.
 */
@DisplayName("ReconciliationResult")
class ReconciliationResultTest {

    @Test
    @DisplayName("Record construction and accessor methods")
    void construction_allFields() {
        Instant now = Instant.now();
        var result = new ReconciliationResult(
                now, "abc123", "hash456",
                ReconciliationOutcome.APPLIED,
                3, 2, 1, 0, List.of("warn1"), null);

        assertThat(result.timestamp()).isEqualTo(now);
        assertThat(result.commitHash()).isEqualTo("abc123");
        assertThat(result.configHash()).isEqualTo("hash456");
        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.APPLIED);
        assertThat(result.routesCreated()).isEqualTo(3);
        assertThat(result.routesUpdated()).isEqualTo(2);
        assertThat(result.filtersCreated()).isEqualTo(1);
        assertThat(result.filtersUpdated()).isEqualTo(0);
        assertThat(result.warnings()).containsExactly("warn1");
        assertThat(result.errorMessage()).isNull();
    }

    @Test
    @DisplayName("Record supports null optional fields")
    void construction_nullOptionalFields() {
        var result = new ReconciliationResult(
                Instant.now(), null, null,
                ReconciliationOutcome.FAILED,
                0, 0, 0, 0, List.of(), "Some error");

        assertThat(result.commitHash()).isNull();
        assertThat(result.configHash()).isNull();
        assertThat(result.errorMessage()).isEqualTo("Some error");
    }

    @Test
    @DisplayName("ReconciliationOutcome enum has exactly 4 values")
    void outcomeEnum_fourValues() {
        assertThat(ReconciliationOutcome.values()).hasSize(4);
    }

    @Test
    @DisplayName("All outcome enum values exist")
    void outcomeEnum_allValues() {
        assertThat(ReconciliationOutcome.valueOf("APPLIED")).isEqualTo(ReconciliationOutcome.APPLIED);
        assertThat(ReconciliationOutcome.valueOf("DRIFT_DETECTED")).isEqualTo(ReconciliationOutcome.DRIFT_DETECTED);
        assertThat(ReconciliationOutcome.valueOf("FAILED")).isEqualTo(ReconciliationOutcome.FAILED);
        assertThat(ReconciliationOutcome.valueOf("NO_CHANGE")).isEqualTo(ReconciliationOutcome.NO_CHANGE);
    }

    @Test
    @DisplayName("Record equality — same values are equal")
    void equality_sameValues() {
        Instant now = Instant.parse("2026-04-08T12:00:00Z");
        var r1 = new ReconciliationResult(now, "abc", "def", ReconciliationOutcome.APPLIED,
                1, 0, 0, 0, List.of(), null);
        var r2 = new ReconciliationResult(now, "abc", "def", ReconciliationOutcome.APPLIED,
                1, 0, 0, 0, List.of(), null);

        assertThat(r1).isEqualTo(r2);
    }

    @Test
    @DisplayName("Record inequality — different values are not equal")
    void inequality_differentOutcome() {
        Instant now = Instant.parse("2026-04-08T12:00:00Z");
        var r1 = new ReconciliationResult(now, "abc", "def", ReconciliationOutcome.APPLIED,
                1, 0, 0, 0, List.of(), null);
        var r2 = new ReconciliationResult(now, "abc", "def", ReconciliationOutcome.FAILED,
                1, 0, 0, 0, List.of(), "error");

        assertThat(r1).isNotEqualTo(r2);
    }
}

