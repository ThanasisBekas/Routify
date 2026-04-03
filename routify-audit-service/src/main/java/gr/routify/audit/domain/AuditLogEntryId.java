package gr.routify.audit.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite primary key for {@link AuditLogEntry}.
 *
 * <p>The {@code audit_log} table is partitioned by {@code occurred_at}, so PostgreSQL
 * requires the partition key to be part of the primary key: {@code (event_id, occurred_at)}.
 * Hibernate needs this {@link java.io.Serializable} key class to correctly issue
 * {@code INSERT} statements instead of attempting a {@code SELECT} by {@code event_id} alone
 * (which would fail on a partitioned table and cause every save to silently roll back).
 */
public class AuditLogEntryId implements Serializable {

    private UUID eventId;
    private Instant occurredAt;

    public AuditLogEntryId() {}

    public AuditLogEntryId(UUID eventId, Instant occurredAt) {
        this.eventId    = eventId;
        this.occurredAt = occurredAt;
    }

    public UUID getEventId()       { return eventId; }
    public Instant getOccurredAt() { return occurredAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AuditLogEntryId other)) return false;
        return Objects.equals(eventId, other.eventId) &&
               Objects.equals(occurredAt, other.occurredAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId, occurredAt);
    }
}

