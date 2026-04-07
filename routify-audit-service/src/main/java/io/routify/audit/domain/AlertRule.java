package io.routify.audit.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Persistent alert rule evaluated on a 60-second cycle by {@code AlertEvaluationScheduler}.
 *
 * <p>State machine: {@code OK → PENDING → FIRING → OK}.
 * <ul>
 *   <li>{@code OK → PENDING}: threshold breached once.</li>
 *   <li>{@code PENDING → FIRING}: breached for consecutive checks ≥ {@code ceil(windowMinutes / evaluationIntervalMinutes)}.</li>
 *   <li>{@code FIRING → OK}: metric drops below threshold.</li>
 * </ul>
 */
@Entity
@Table(
    name = "alert_rule",
    schema = "routify_audit",
    indexes = {
        @Index(name = "idx_alert_rule_tenant", columnList = "tenant_id, enabled")
    }
)
public class AlertRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false, length = 50)
    private String metric;

    @Column(name = "route_id")
    private UUID routeId;

    @Column(nullable = false, length = 10)
    private String operator;

    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal threshold;

    @Column(name = "window_minutes", nullable = false)
    private int windowMinutes = 5;

    @Column(name = "cooldown_minutes", nullable = false)
    private int cooldownMinutes = 30;

    @Column(nullable = false, length = 20)
    private String severity = "WARNING";

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "current_state", nullable = false, length = 20)
    private String currentState = "OK";

    @Column(name = "state_changed_at")
    private Instant stateChangedAt;

    @Column(name = "consecutive_breaches", nullable = false)
    private int consecutiveBreaches = 0;

    @Column(name = "last_evaluated_at")
    private Instant lastEvaluatedAt;

    @Column(name = "last_fired_at")
    private Instant lastFiredAt;

    @Column(name = "muted_until")
    private Instant mutedUntil;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AlertRule() {}

    // ─── Getters ───────────────────────────────────────────────────────────────

    public UUID getId()               { return id; }
    public UUID getTenantId()         { return tenantId; }
    public String getName()           { return name; }
    public String getDescription()    { return description; }
    public String getMetric()         { return metric; }
    public UUID getRouteId()          { return routeId; }
    public String getOperator()       { return operator; }
    public BigDecimal getThreshold()  { return threshold; }
    public int getWindowMinutes()     { return windowMinutes; }
    public int getCooldownMinutes()   { return cooldownMinutes; }
    public String getSeverity()       { return severity; }
    public boolean isEnabled()        { return enabled; }
    public String getCurrentState()   { return currentState; }
    public Instant getStateChangedAt(){ return stateChangedAt; }
    public int getConsecutiveBreaches() { return consecutiveBreaches; }
    public Instant getLastEvaluatedAt(){ return lastEvaluatedAt; }
    public Instant getLastFiredAt()   { return lastFiredAt; }
    public Instant getMutedUntil()    { return mutedUntil; }
    public String getCreatedBy()      { return createdBy; }
    public Instant getCreatedAt()     { return createdAt; }
    public Instant getUpdatedAt()     { return updatedAt; }

    // ─── Setters (state machine + CRUD) ────────────────────────────────────────

    public void setName(String name)                   { this.name = name; }
    public void setDescription(String description)     { this.description = description; }
    public void setMetric(String metric)               { this.metric = metric; }
    public void setRouteId(UUID routeId)               { this.routeId = routeId; }
    public void setOperator(String operator)           { this.operator = operator; }
    public void setThreshold(BigDecimal threshold)     { this.threshold = threshold; }
    public void setWindowMinutes(int windowMinutes)    { this.windowMinutes = windowMinutes; }
    public void setCooldownMinutes(int cooldownMinutes){ this.cooldownMinutes = cooldownMinutes; }
    public void setSeverity(String severity)           { this.severity = severity; }
    public void setEnabled(boolean enabled)            { this.enabled = enabled; }
    public void setCurrentState(String state)          { this.currentState = state; }
    public void setStateChangedAt(Instant at)          { this.stateChangedAt = at; }
    public void setConsecutiveBreaches(int n)          { this.consecutiveBreaches = n; }
    public void setLastEvaluatedAt(Instant at)         { this.lastEvaluatedAt = at; }
    public void setLastFiredAt(Instant at)             { this.lastFiredAt = at; }
    public void setMutedUntil(Instant mutedUntil)      { this.mutedUntil = mutedUntil; }
    public void setCreatedBy(String createdBy)         { this.createdBy = createdBy; }

    // ─── Builder ───────────────────────────────────────────────────────────────

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final AlertRule r = new AlertRule();

        public Builder tenantId(UUID v)          { r.tenantId = v; return this; }
        public Builder name(String v)            { r.name = v; return this; }
        public Builder description(String v)     { r.description = v; return this; }
        public Builder metric(String v)          { r.metric = v; return this; }
        public Builder routeId(UUID v)           { r.routeId = v; return this; }
        public Builder operator(String v)        { r.operator = v; return this; }
        public Builder threshold(BigDecimal v)   { r.threshold = v; return this; }
        public Builder windowMinutes(int v)      { r.windowMinutes = v; return this; }
        public Builder cooldownMinutes(int v)    { r.cooldownMinutes = v; return this; }
        public Builder severity(String v)        { r.severity = v; return this; }
        public Builder enabled(boolean v)        { r.enabled = v; return this; }
        public Builder createdBy(String v)       { r.createdBy = v; return this; }
        public AlertRule build()                 { return r; }
    }
}

