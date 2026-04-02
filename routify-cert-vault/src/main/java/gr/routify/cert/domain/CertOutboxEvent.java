package gr.routify.cert.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Transactional Outbox event for reliable Kafka publishing.
 * Mirrors the pattern used in routify-route-service.
 */
@Entity
@Table(name = "cert_outbox_event", schema = "routify_cert")
@Getter
@Setter
@NoArgsConstructor
public class CertOutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 36)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "topic", nullable = false, length = 200)
    private String topic;

    @Column(name = "partition_key", length = 36)
    private String partitionKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "PENDING";

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "published_at")
    private Instant publishedAt;

    public static CertOutboxEvent of(String aggregateType, String aggregateId,
                                     String eventType, String topic,
                                     String partitionKey, String payload) {
        var event = new CertOutboxEvent();
        event.aggregateType = aggregateType;
        event.aggregateId   = aggregateId;
        event.eventType     = eventType;
        event.topic         = topic;
        event.partitionKey  = partitionKey;
        event.payload       = payload;
        event.status        = "PENDING";
        return event;
    }

    public void markPublished() {
        this.status      = "PUBLISHED";
        this.publishedAt = Instant.now();
    }

    public void markFailed(String error) {
        this.status     = "FAILED";
        this.retryCount = this.retryCount + 1;
        this.lastError  = error != null && error.length() > 1000 ? error.substring(0, 1000) : error;
    }

    public void resetToPending() {
        this.status = "PENDING";
    }
}

