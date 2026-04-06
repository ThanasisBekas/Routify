package io.routify.audit.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable audit log entry for every domain event.
 *
 * <p>Records every route/filter/tenant/user change for compliance,
 * debugging, and audit trail requirements. Uses PostgreSQL native partitioning
 * by month for efficient time-based queries and retention management.
 *
 * <p><b>Composite PK note:</b> The {@code audit_log} table is partitioned by
 * {@code occurred_at}. PostgreSQL requires the partition key to be part of the
 * primary key, so the PK is {@code (event_id, occurred_at)}. The {@link AuditLogEntryId}
 * {@code @IdClass} tells Hibernate to issue a direct {@code INSERT} instead of first
 * doing a {@code SELECT} by {@code event_id} alone (which fails on partitioned tables).
 */
@Entity
@IdClass(AuditLogEntryId.class)
@Table(
    name = "audit_log",
    schema = "routify_audit",
    indexes = {
        @Index(name = "idx_audit_tenant_time", columnList = "tenant_id, occurred_at DESC"),
        @Index(name = "idx_audit_event_type",  columnList = "event_type, occurred_at DESC"),
        @Index(name = "idx_audit_aggregate",   columnList = "aggregate_type, aggregate_id")
    }
)
public class AuditLogEntry {

    @Id
    @Column(name = "event_id", updatable = false, nullable = false)
    private UUID eventId;

    @Column(name = "tenant_id", updatable = false)
    private UUID tenantId;

    @Column(name = "event_type", nullable = false, length = 100, updatable = false)
    private String eventType;

    @Column(name = "aggregate_type", nullable = false, length = 100, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", length = 36, updatable = false)
    private String aggregateId;

    /** The actor who triggered this event (user ID or system) */
    @Column(name = "actor_id", length = 36, updatable = false)
    private String actorId;

    /** Full event JSON payload for replay and debugging */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb", updatable = false)
    private String payload;

    @Column(name = "correlation_id", length = 36, updatable = false)
    private String correlationId;

    @Id
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @CreationTimestamp
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    protected AuditLogEntry() {}

    public AuditLogEntry(UUID eventId, UUID tenantId, String eventType,
                         String aggregateType, String aggregateId,
                         String actorId, String payload,
                         String correlationId, Instant occurredAt) {
        this.eventId       = eventId;
        this.tenantId      = tenantId;
        this.eventType     = eventType;
        this.aggregateType = aggregateType;
        this.aggregateId   = aggregateId;
        this.actorId       = actorId;
        this.payload       = payload;
        this.correlationId = correlationId;
        this.occurredAt    = occurredAt;
    }

    public UUID getId()             { return eventId; }
    public UUID getTenantId()       { return tenantId; }
    public String getEventType()    { return eventType; }
    public String getAggregateType(){ return aggregateType; }
    public String getAggregateId()  { return aggregateId; }
    public String getActorId()      { return actorId; }
    public String getPayload()      { return payload; }
    public String getCorrelationId(){ return correlationId; }
    public Instant getOccurredAt()  { return occurredAt; }
    public Instant getRecordedAt()  { return recordedAt; }
}

