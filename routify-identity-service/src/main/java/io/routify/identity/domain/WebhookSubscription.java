package io.routify.identity.domain;

import io.routify.common.crypto.Sensitive;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Tenant-scoped webhook subscription entity.
 *
 * <p>When platform events matching the registered {@link #eventTypes} occur,
 * the {@link io.routify.identity.service.WebhookDispatcher} delivers an HTTP POST
 * to {@link #url} with an HMAC-SHA256 signature computed from {@link #secret}.
 *
 * <p>If deliveries fail repeatedly, the subscription is auto-suspended
 * when {@link #failureCount} reaches 10.
 */
@Entity
@Table(name = "webhook_subscription", schema = "routify_identity")
@Getter @Setter
public class WebhookSubscription {

    public enum Status { ACTIVE, SUSPENDED, DELETED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 2048)
    private String url;

    /** HMAC-SHA256 signing key for delivery verification — encrypted at rest. */
    @Sensitive
    @Column(nullable = false, columnDefinition = "TEXT")
    private String secret;

    /** Platform event types this subscription listens to. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "event_types", nullable = false, columnDefinition = "text[]")
    private List<String> eventTypes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    @Column(name = "last_delivered_at")
    private Instant lastDeliveredAt;

    @Column(name = "created_by")
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WebhookSubscription() {}

    public WebhookSubscription(UUID tenantId, String name, String url, String secret,
                               List<String> eventTypes, UUID createdBy) {
        this.tenantId     = tenantId;
        this.name         = name;
        this.url          = url;
        this.secret       = secret;
        this.eventTypes   = eventTypes;
        this.status       = Status.ACTIVE;
        this.failureCount = 0;
        this.createdBy    = createdBy;
        this.updatedAt    = Instant.now();
    }

    public void suspend() {
        this.status = Status.SUSPENDED;
        this.updatedAt = Instant.now();
    }

    public void resetFailureCount() {
        this.failureCount = 0;
        this.updatedAt = Instant.now();
    }

    public void incrementFailureCount() {
        this.failureCount++;
        this.updatedAt = Instant.now();
    }

    public void markDelivered() {
        this.lastDeliveredAt = Instant.now();
        this.failureCount = 0;
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    private void onUpdate() {
        this.updatedAt = Instant.now();
    }
}

