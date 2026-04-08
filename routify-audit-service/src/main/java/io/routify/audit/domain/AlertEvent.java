package io.routify.audit.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable record of an alert state transition (e.g. PENDING → FIRING).
 *
 * <p>Used for the alert history timeline in the dashboard and for audit purposes.
 */
@Entity
@Table(
    name = "alert_event",
    schema = "routify_audit",
    indexes = {
        @Index(name = "idx_alert_event_rule", columnList = "rule_id, occurred_at DESC")
    }
)
public class AlertEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "rule_id", nullable = false, updatable = false)
    private UUID ruleId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 30, updatable = false)
    private String transition;

    @Column(name = "metric_value", precision = 12, scale = 4, updatable = false)
    private BigDecimal metricValue;

    @Column(precision = 12, scale = 4, updatable = false)
    private BigDecimal threshold;

    @Column(length = 1000, updatable = false)
    private String message;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected AlertEvent() {}

    public AlertEvent(UUID ruleId, UUID tenantId, String transition,
                      BigDecimal metricValue, BigDecimal threshold,
                      String message, Instant occurredAt) {
        this.ruleId      = ruleId;
        this.tenantId    = tenantId;
        this.transition  = transition;
        this.metricValue = metricValue;
        this.threshold   = threshold;
        this.message     = message;
        this.occurredAt  = occurredAt;
    }

    public UUID getId()               { return id; }
    public UUID getRuleId()           { return ruleId; }
    public UUID getTenantId()         { return tenantId; }
    public String getTransition()     { return transition; }
    public BigDecimal getMetricValue(){ return metricValue; }
    public BigDecimal getThreshold()  { return threshold; }
    public String getMessage()        { return message; }
    public Instant getOccurredAt()    { return occurredAt; }
}

