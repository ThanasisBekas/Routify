package io.routify.audit.domain;

import io.routify.audit.consumer.AiFilterDecisionConsumer;
import io.routify.audit.scheduler.AuditRetentionScheduler;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable audit record for a single AI filter evaluation decision.
 *
 * <p>Every verdict produced by {@code routify-ai-service} — whether ALLOW, BLOCK,
 * FLAG, or a circuit-breaker fallback — is published to the Kafka topic
 * {@code routify.ai.filter.decisions} and persisted here by
 * {@link AiFilterDecisionConsumer}.
 *
 * <p>Table is range-partitioned by {@code evaluated_at} (monthly). Records are
 * immutable after insertion — no update operations are performed.
 *
 * <p>Retention: controlled by {@link AuditRetentionScheduler}.
 * Default: 30 days (same as {@code request_log}).
 */
@Entity
@Table(
    name = "ai_filter_decision",
    schema = "routify_audit",
    indexes = {
        @Index(name = "idx_ai_decision_tenant_time", columnList = "tenant_id, evaluated_at DESC"),
        @Index(name = "idx_ai_decision_route_time",  columnList = "route_id, evaluated_at DESC"),
        @Index(name = "idx_ai_decision_action",      columnList = "action, evaluated_at DESC"),
        @Index(name = "idx_ai_decision_eval_id",     columnList = "evaluation_id")
    }
)
public class AiFilterDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    /**
     * Unique evaluation trace ID — correlates this DB record with:
     * <ul>
     *   <li>The {@code X-AI-Filter-Eval-Id} header injected by the gateway</li>
     *   <li>The {@code evaluationId} field in the API response</li>
     *   <li>Distributed traces in Jaeger/Tempo</li>
     * </ul>
     */
    @Column(name = "evaluation_id", length = 36, updatable = false)
    private String evaluationId;

    @Column(name = "route_id", updatable = false)
    private UUID routeId;

    @Column(name = "route_name", length = 255, updatable = false)
    private String routeName;

    @Column(name = "tenant_id", updatable = false)
    private UUID tenantId;

    /** ALLOW | BLOCK | FLAG */
    @Column(name = "action", length = 10, nullable = false, updatable = false)
    private String action;

    /** One-sentence LLM explanation (null for fallback verdicts). */
    @Column(name = "reason", columnDefinition = "text", updatable = false)
    private String reason;

    /** LLM confidence score 0.0–1.0. 0.0 = fallback/circuit-open verdict. */
    @Column(name = "confidence", nullable = false, updatable = false)
    private double confidence;

    /** True when served from Redis verdict cache — no LLM call was made. */
    @Column(name = "cached", nullable = false, updatable = false)
    private boolean cached;

    /** SYNC | ASYNC */
    @Column(name = "evaluation_mode", length = 10, nullable = false, updatable = false)
    private String evaluationMode;

    /** Total evaluation latency in milliseconds (includes cache lookup time). */
    @Column(name = "latency_ms", updatable = false)
    private Long latencyMs;

    @Column(name = "method", length = 10, updatable = false)
    private String method;

    @Column(name = "path", length = 500, updatable = false)
    private String path;

    @Column(name = "client_ip", length = 45, updatable = false)
    private String clientIp;

    /** When the evaluation was performed (from the AI service event). */
    @Column(name = "evaluated_at", nullable = false, updatable = false)
    private Instant evaluatedAt;

    /** When this record was inserted into the audit DB. */
    @CreationTimestamp
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    protected AiFilterDecision() {}

    public static Builder builder() { return new Builder(); }

    // ─── Getters ─────────────────────────────────────────────────────────────

    public UUID    getId()             { return id; }
    public String  getEvaluationId()   { return evaluationId; }
    public UUID    getRouteId()        { return routeId; }
    public String  getRouteName()      { return routeName; }
    public UUID    getTenantId()       { return tenantId; }
    public String  getAction()         { return action; }
    public String  getReason()         { return reason; }
    public double  getConfidence()     { return confidence; }
    public boolean isCached()          { return cached; }
    public String  getEvaluationMode() { return evaluationMode; }
    public Long    getLatencyMs()      { return latencyMs; }
    public String  getMethod()         { return method; }
    public String  getPath()           { return path; }
    public String  getClientIp()       { return clientIp; }
    public Instant getEvaluatedAt()    { return evaluatedAt; }
    public Instant getRecordedAt()     { return recordedAt; }

    // ─── Builder ──────────────────────────────────────────────────────────────

    public static final class Builder {
        private final AiFilterDecision d = new AiFilterDecision();

        public Builder evaluationId(String v)   { d.evaluationId   = v; return this; }
        public Builder routeId(UUID v)          { d.routeId        = v; return this; }
        public Builder routeName(String v)      { d.routeName      = v; return this; }
        public Builder tenantId(UUID v)         { d.tenantId       = v; return this; }
        public Builder action(String v)         { d.action         = v; return this; }
        public Builder reason(String v)         { d.reason         = v; return this; }
        public Builder confidence(double v)     { d.confidence     = v; return this; }
        public Builder cached(boolean v)        { d.cached         = v; return this; }
        public Builder evaluationMode(String v) { d.evaluationMode = v; return this; }
        public Builder latencyMs(Long v)        { d.latencyMs      = v; return this; }
        public Builder method(String v)         { d.method         = v; return this; }
        public Builder path(String v)           { d.path           = v; return this; }
        public Builder clientIp(String v)       { d.clientIp       = v; return this; }
        public Builder evaluatedAt(Instant v)   { d.evaluatedAt    = v; return this; }
        public AiFilterDecision build()         { return d; }
    }
}

