package gr.routify.audit.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite primary key for {@link DlqEvent}.
 *
 * <p>The {@code dlq_event} table is partitioned by {@code failed_at}, so PostgreSQL
 * requires the partition key to be part of the primary key: {@code (id, failed_at)}.
 */
public class DlqEventId implements Serializable {

    private UUID id;
    private Instant failedAt;

    public DlqEventId() {}

    public DlqEventId(UUID id, Instant failedAt) {
        this.id       = id;
        this.failedAt = failedAt;
    }

    public UUID getId()          { return id; }
    public Instant getFailedAt() { return failedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DlqEventId other)) return false;
        return Objects.equals(id, other.id) &&
               Objects.equals(failedAt, other.failedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, failedAt);
    }
}

