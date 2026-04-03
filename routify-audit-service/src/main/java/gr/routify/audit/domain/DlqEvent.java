package gr.routify.audit.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Persists every Kafka record that was forwarded to a {@code .DLQ} topic by any Routify service.
 *
 * <p>DLQ records arrive because a consumer exhausted its retry budget or encountered a
 * non-retryable exception (e.g. malformed JSON). Storing them here provides:
 * <ul>
 *   <li>Full visibility into processing failures across every service</li>
 *   <li>A durable corpus for post-mortem analysis and manual reprocessing</li>
 *   <li>Alerting surface — query {@code dlq_event} for recent spikes</li>
 * </ul>
 *
 * <p><b>Composite PK note:</b> partitioned by {@code failed_at}; PostgreSQL requires
 * the partition key in the primary key → {@code (id, failed_at)}.
 *
 * <p><b>Raw payload:</b> stored as {@code TEXT} (not {@code JSONB}) because the record
 * may have arrived on the DLQ precisely because its payload could not be parsed.
 */
@Entity
@IdClass(DlqEventId.class)
@Table(
    name = "dlq_event",
    schema = "routify_audit",
    indexes = {
        @Index(name = "idx_dlq_source_topic", columnList = "source_topic, failed_at DESC"),
        @Index(name = "idx_dlq_failed_at",    columnList = "failed_at DESC")
    }
)
public class DlqEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    /** Original topic (without the {@code .DLQ} suffix). */
    @Column(name = "source_topic", nullable = false, length = 200, updatable = false)
    private String sourceTopic;

    /** Actual DLQ topic the record landed on, e.g. {@code routify.route.events.DLQ}. */
    @Column(name = "dlq_topic", nullable = false, length = 200, updatable = false)
    private String dlqTopic;

    @Column(name = "partition_num", updatable = false)
    private Integer partitionNum;

    @Column(name = "kafka_offset", updatable = false)
    private Long kafkaOffset;

    @Column(name = "record_key", length = 500, updatable = false)
    private String recordKey;

    /**
     * Raw message value as received — preserved as TEXT in case JSON parsing failed.
     * May be null if the record had no value.
     */
    @Column(name = "raw_payload", columnDefinition = "text", updatable = false)
    private String rawPayload;

    /** Exception message that caused the record to be dead-lettered. */
    @Column(name = "error_message", columnDefinition = "text", updatable = false)
    private String errorMessage;

    /** Fully-qualified class name of the exception. */
    @Column(name = "error_class", length = 500, updatable = false)
    private String errorClass;

    @Id
    @Column(name = "failed_at", nullable = false, updatable = false)
    private Instant failedAt;

    @CreationTimestamp
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    protected DlqEvent() {}

    public static Builder builder() { return new Builder(); }

    public UUID getId()            { return id; }
    public String getSourceTopic() { return sourceTopic; }
    public String getDlqTopic()    { return dlqTopic; }
    public Integer getPartitionNum(){ return partitionNum; }
    public Long getKafkaOffset()   { return kafkaOffset; }
    public String getRecordKey()   { return recordKey; }
    public String getRawPayload()  { return rawPayload; }
    public String getErrorMessage(){ return errorMessage; }
    public String getErrorClass()  { return errorClass; }
    public Instant getFailedAt()   { return failedAt; }
    public Instant getRecordedAt() { return recordedAt; }

    public static final class Builder {
        private final DlqEvent e = new DlqEvent();

        public Builder sourceTopic(String v)  { e.sourceTopic  = v; return this; }
        public Builder dlqTopic(String v)     { e.dlqTopic     = v; return this; }
        public Builder partitionNum(int v)    { e.partitionNum = v; return this; }
        public Builder kafkaOffset(long v)    { e.kafkaOffset  = v; return this; }
        public Builder recordKey(String v)    { e.recordKey    = v; return this; }
        public Builder rawPayload(String v)   { e.rawPayload   = v; return this; }
        public Builder errorMessage(String v) { e.errorMessage = v; return this; }
        public Builder errorClass(String v)   { e.errorClass   = v; return this; }
        public Builder failedAt(Instant v)    { e.failedAt     = v; return this; }
        public DlqEvent build()               { return e; }
    }
}

