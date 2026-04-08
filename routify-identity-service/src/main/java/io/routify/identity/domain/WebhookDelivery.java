package io.routify.identity.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Webhook delivery attempt record.
 *
 * <p>Every attempt to deliver a webhook event is logged here. Failed deliveries
 * are retried with exponential backoff (30 s → 2 min → 15 min). After 3 failed
 * attempts the delivery is marked {@code FAILED}.
 *
 * <p>Retention: rows older than 7 days are purged nightly by the
 * {@link io.routify.identity.service.WebhookService} cleanup scheduler.
 */
@Entity
@Table(name = "webhook_delivery", schema = "routify_identity")
@Getter @Setter
public class WebhookDelivery {

    public enum Status { PENDING, DELIVERED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body")
    private String responseBody;

    @Column(nullable = false)
    private int attempt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "error_message")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected WebhookDelivery() {}

    public WebhookDelivery(UUID subscriptionId, String eventType, String payload) {
        this.subscriptionId = subscriptionId;
        this.eventType      = eventType;
        this.payload         = payload;
        this.attempt         = 1;
        this.status          = Status.PENDING;
    }

    public void markDelivered(int httpStatus, String body) {
        this.status         = Status.DELIVERED;
        this.responseStatus = httpStatus;
        this.responseBody   = truncate(body, 4000);
        this.deliveredAt    = Instant.now();
    }

    public void markFailed(String error, Integer httpStatus, String body) {
        this.status         = Status.FAILED;
        this.errorMessage   = truncate(error, 2000);
        this.responseStatus = httpStatus;
        this.responseBody   = truncate(body, 4000);
    }

    public void markPendingRetry(Instant nextRetry) {
        this.status      = Status.PENDING;
        this.nextRetryAt = nextRetry;
        this.attempt++;
    }

    private static String truncate(String s, int max) {
        return (s != null && s.length() > max) ? s.substring(0, max) : s;
    }
}

