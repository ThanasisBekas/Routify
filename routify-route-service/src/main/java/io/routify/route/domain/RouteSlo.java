package io.routify.route.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * SLO (Service Level Objective) configuration for a route.
 *
 * <p>Defines availability and latency targets that the Gateway Health Dashboard v2
 * uses to compute error budgets and SLO compliance indicators.
 */
@Entity
@Table(name = "route_slo", schema = "routify")
public class RouteSlo {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "route_id", nullable = false, unique = true)
    private UUID routeId;

    @Column(name = "availability_target", nullable = false, precision = 5, scale = 2)
    private BigDecimal availabilityTarget = new BigDecimal("99.90");

    @Column(name = "latency_p99_target_ms", nullable = false)
    private int latencyP99TargetMs = 1000;

    @Column(name = "evaluation_window_hours", nullable = false)
    private int evaluationWindowHours = 168;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RouteSlo() {}

    public RouteSlo(UUID routeId, BigDecimal availabilityTarget, int latencyP99TargetMs, int evaluationWindowHours) {
        this.routeId = routeId;
        this.availabilityTarget = availabilityTarget;
        this.latencyP99TargetMs = latencyP99TargetMs;
        this.evaluationWindowHours = evaluationWindowHours;
    }

    // ─── Getters & Setters ─────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public UUID getRouteId() { return routeId; }
    public BigDecimal getAvailabilityTarget() { return availabilityTarget; }
    public int getLatencyP99TargetMs() { return latencyP99TargetMs; }
    public int getEvaluationWindowHours() { return evaluationWindowHours; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setAvailabilityTarget(BigDecimal availabilityTarget) { this.availabilityTarget = availabilityTarget; }
    public void setLatencyP99TargetMs(int latencyP99TargetMs) { this.latencyP99TargetMs = latencyP99TargetMs; }
    public void setEvaluationWindowHours(int evaluationWindowHours) { this.evaluationWindowHours = evaluationWindowHours; }
}

