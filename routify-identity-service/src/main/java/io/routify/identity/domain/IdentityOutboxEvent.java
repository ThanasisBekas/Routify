package io.routify.identity.domain;

import io.routify.identity.outbox.IdentityOutboxPoller;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Transactional Outbox entry for the identity-service.
 *
 * <p>Fixes the dual-write anti-pattern where TenantService / UserService were calling
 * {@code kafkaTemplate.send()} directly inside a {@code @Transactional} method. If Kafka
 * was unavailable at commit time the event was permanently lost — silently swallowed by
 * the {@code catch (Exception e) { log.error(...) }} block.
 *
 * <p>The new pattern:
 * <ol>
 *   <li>Domain mutation + IdentityOutboxEvent are persisted in the <b>same DB transaction</b>.</li>
 *   <li>{@link IdentityOutboxPoller} reads PENDING entries every 250 ms and publishes to Kafka.</li>
 *   <li>On successful ACK the entry is marked PUBLISHED — guaranteeing at-least-once delivery.</li>
 * </ol>
 */
@Entity
@Table(
    name = "outbox_event",
    schema = "routify_identity",
    indexes = {
        @Index(name = "idx_identity_outbox_pending",   columnList = "status, created_at"),
        @Index(name = "idx_identity_outbox_aggregate", columnList = "aggregate_type, aggregate_id")
    }
)
public class IdentityOutboxEvent {

    public enum Status { PENDING, PUBLISHED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    /** Domain aggregate type — "Tenant", "User" */
    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    /** Domain aggregate UUID */
    @Column(name = "aggregate_id", nullable = false, length = 36)
    private String aggregateId;

    /** Event class simple name (e.g. "TenantCreated") */
    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    /** Kafka topic to publish to */
    @Column(nullable = false, length = 200)
    private String topic;

    /** Kafka partition key (tenant ID for tenant isolation) */
    @Column(name = "partition_key", length = 36)
    private String partitionKey;

    /** Serialised JSON event payload (typed DomainEvent with @type discriminator) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "published_at")
    private Instant publishedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IdentityOutboxEvent() {}

    public IdentityOutboxEvent(String aggregateType, String aggregateId, String eventType,
                               String topic, String partitionKey, String payload) {
        this.aggregateType = aggregateType;
        this.aggregateId   = aggregateId;
        this.eventType     = eventType;
        this.topic         = topic;
        this.partitionKey  = partitionKey;
        this.payload       = payload;
        this.status        = Status.PENDING;
        this.retryCount    = 0;
    }

    public void markPublished() {
        this.status      = Status.PUBLISHED;
        this.publishedAt = Instant.now();
    }

    public void markFailed(String error) {
        this.status     = Status.FAILED;
        this.lastError  = truncate(error, 1000);
        this.retryCount = this.retryCount + 1;
    }

    public void resetToPending() { this.status = Status.PENDING; }

    public UUID getId()              { return id; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId()   { return aggregateId; }
    public String getEventType()     { return eventType; }
    public String getTopic()         { return topic; }
    public String getPartitionKey()  { return partitionKey; }
    public String getPayload()       { return payload; }
    public Status getStatus()        { return status; }
    public Integer getRetryCount()   { return retryCount; }
    public String getLastError()     { return lastError; }
    public Instant getPublishedAt()  { return publishedAt; }
    public Instant getCreatedAt()    { return createdAt; }

    private static String truncate(String s, int max) {
        return (s != null && s.length() > max) ? s.substring(0, max) : s;
    }
}

