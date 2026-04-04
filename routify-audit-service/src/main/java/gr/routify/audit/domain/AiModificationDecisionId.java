package gr.routify.audit.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite primary key for {@link AiModificationDecision}.
 *
 * <p>The {@code ai_modifier_decision} table is partitioned by RANGE(evaluated_at).
 * PostgreSQL requires the partition key column to be part of the primary key.
 * This {@code @IdClass} enables Hibernate to issue a direct INSERT without first
 * performing a SELECT-by-id (which fails on partitioned tables because the partition
 * key is unknown at SELECT time).
 *
 * <p>Mirrors the pattern used by {@link AuditLogEntryId} and the implicit composite PK
 * on {@link RequestLog} — all three tables use the same (id, time_column) PK shape.
 */
public class AiModificationDecisionId implements Serializable {

    private UUID id;
    private Instant evaluatedAt;

    public AiModificationDecisionId() {}

    public AiModificationDecisionId(UUID id, Instant evaluatedAt) {
        this.id          = id;
        this.evaluatedAt = evaluatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AiModificationDecisionId that)) return false;
        return Objects.equals(id, that.id) && Objects.equals(evaluatedAt, that.evaluatedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, evaluatedAt);
    }
}

