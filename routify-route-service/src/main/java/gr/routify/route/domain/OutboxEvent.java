package gr.routify.route.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Transactional Outbox entry for reliable event publishing.
 *
 * <p>The Outbox pattern guarantees at-least-once delivery to Kafka:
 * <ol>
 *   <li>Domain operation + OutboxEvent are committed in the same DB transaction</li>
 *   <li>A scheduled poller reads PENDING outbox entries and publishes to Kafka</li>
 *   <li>On successful publish, the entry is marked PUBLISHED</li>
 * </ol>
 *
 * <p>This prevents the dual-write problem where the DB commit succeeds but
 * Kafka publish fails (or vice versa), ensuring event consistency.
 */
@Entity
@Table(
    name = "outbox_event",
    schema = "routify",
    indexes = {
        @Index(name = "idx_outbox_status_created", columnList = "status, created_at"),
        @Index(name = "idx_outbox_aggregate", columnList = "aggregate_type, aggregate_id")
    }
)
public class OutboxEvent {

    public enum Status { PENDING, PUBLISHED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    /** Domain aggregate type (e.g. "Route", "FilterDefinition", "Tenant") */
    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    /** Domain aggregate ID */
    @Column(name = "aggregate_id", nullable = false, length = 36)
    private String aggregateId;

    /** Event type (e.g. "ROUTE_ACTIVATED", "FILTER_CREATED") */
    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    /** Kafka topic to publish to */
    @Column(name = "topic", nullable = false, length = 200)
    private String topic;

    /** Kafka partition key (tenant ID for partitioning by tenant) */
    @Column(name = "partition_key", length = 36)
    private String partitionKey;

    /** Serialised JSON event payload */
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

    protected OutboxEvent() {}

    public OutboxEvent(String aggregateType, String aggregateId, String eventType,
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
        this.lastError  = error;
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
}

