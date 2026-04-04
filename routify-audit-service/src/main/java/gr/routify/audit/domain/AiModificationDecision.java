package gr.routify.audit.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Immutable audit record for a single AI Modification Filter evaluation.
 *
 * <p>Every mutation produced by {@code routify-ai-service} — whether a mutation was applied
 * or a passthrough occurred — is published to the Kafka topic
 * {@code routify.ai.modification.events} and persisted here by
 * {@link gr.routify.audit.consumer.AiModificationDecisionConsumer}.
 *
 * <h3>PII-safe design</h3>
 * The original and mutated request bodies are <strong>never</strong> stored here.
 * Only SHA-256 hashes are persisted, making the table safe for long-term retention
 * without risk of PII leakage in audit logs.
 *
 * <p>Records are immutable after insertion — no update operations are performed.
 */
@Entity
@Table(
    name = "ai_modifier_decision",
    schema = "routify_audit",
    indexes = {
        @Index(name = "idx_ai_mod_tenant_time",  columnList = "tenant_id, evaluated_at DESC"),
        @Index(name = "idx_ai_mod_route_time",   columnList = "route_id, evaluated_at DESC"),
        @Index(name = "idx_ai_mod_mutation_id",  columnList = "mutation_id"),
        @Index(name = "idx_ai_mod_type",         columnList = "mutation_type, evaluated_at DESC")
    }
)
public class AiModificationDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    /**
     * Unique mutation trace ID — correlates this DB record with:
     * <ul>
     *   <li>The {@code X-AI-Modifier-Id} header injected by the gateway</li>
     *   <li>Distributed traces in Jaeger/Tempo</li>
     * </ul>
     */
    @Column(name = "mutation_id", length = 36, updatable = false)
    private String mutationId;

    @Column(name = "route_id", updatable = false)
    private UUID routeId;

    @Column(name = "route_name", length = 255, updatable = false)
    private String routeName;

    @Column(name = "tenant_id", updatable = false)
    private UUID tenantId;

    /** True when the LLM produced a valid mutation; false = passthrough. */
    @Column(name = "mutation_applied", nullable = false, updatable = false)
    private boolean mutationApplied;

    /** PII_SCRUB | TRANSLATE | HEADER_REWRITE | CUSTOM | PASSTHROUGH */
    @Column(name = "mutation_type", length = 30, updatable = false)
    private String mutationType;

    /** One-sentence LLM explanation (null for passthrough). */
    @Column(name = "reason", columnDefinition = "text", updatable = false)
    private String reason;

    /**
     * SHA-256 hex of the original request body bytes.
     * {@code null} when body was not included in the evaluation.
     */
    @Column(name = "original_body_hash", length = 64, updatable = false)
    private String originalBodyHash;

    /**
     * SHA-256 hex of the mutated request body bytes.
     * {@code null} when mutationApplied=false or body was not mutated.
     */
    @Column(name = "mutated_body_hash", length = 64, updatable = false)
    private String mutatedBodyHash;

    /**
     * List of header names (not values) that were modified.
     * Empty when only body was mutated or no headers changed.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "headers_modified", columnDefinition = "jsonb", updatable = false)
    private List<String> headersModified;

    /** True when served from Redis mutation cache — no LLM call was made. */
    @Column(name = "cached", nullable = false, updatable = false)
    private boolean cached;

    /** Total evaluation latency in milliseconds. */
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

    protected AiModificationDecision() {}

    public static Builder builder() { return new Builder(); }

    // ─── Getters ─────────────────────────────────────────────────────────────

    public UUID         getId()              { return id; }
    public String       getMutationId()      { return mutationId; }
    public UUID         getRouteId()         { return routeId; }
    public String       getRouteName()       { return routeName; }
    public UUID         getTenantId()        { return tenantId; }
    public boolean      isMutationApplied()  { return mutationApplied; }
    public String       getMutationType()    { return mutationType; }
    public String       getReason()          { return reason; }
    public String       getOriginalBodyHash(){ return originalBodyHash; }
    public String       getMutatedBodyHash() { return mutatedBodyHash; }
    public List<String> getHeadersModified() { return headersModified; }
    public boolean      isCached()           { return cached; }
    public Long         getLatencyMs()       { return latencyMs; }
    public String       getMethod()          { return method; }
    public String       getPath()            { return path; }
    public String       getClientIp()        { return clientIp; }
    public Instant      getEvaluatedAt()     { return evaluatedAt; }
    public Instant      getRecordedAt()      { return recordedAt; }

    // ─── Builder ──────────────────────────────────────────────────────────────

    public static final class Builder {
        private final AiModificationDecision d = new AiModificationDecision();

        public Builder mutationId(String v)           { d.mutationId       = v; return this; }
        public Builder routeId(UUID v)                { d.routeId          = v; return this; }
        public Builder routeName(String v)            { d.routeName        = v; return this; }
        public Builder tenantId(UUID v)               { d.tenantId         = v; return this; }
        public Builder mutationApplied(boolean v)     { d.mutationApplied  = v; return this; }
        public Builder mutationType(String v)         { d.mutationType     = v; return this; }
        public Builder reason(String v)               { d.reason           = v; return this; }
        public Builder originalBodyHash(String v)     { d.originalBodyHash = v; return this; }
        public Builder mutatedBodyHash(String v)      { d.mutatedBodyHash  = v; return this; }
        public Builder headersModified(List<String> v){ d.headersModified  = v; return this; }
        public Builder cached(boolean v)              { d.cached           = v; return this; }
        public Builder latencyMs(Long v)              { d.latencyMs        = v; return this; }
        public Builder method(String v)               { d.method           = v; return this; }
        public Builder path(String v)                 { d.path             = v; return this; }
        public Builder clientIp(String v)             { d.clientIp         = v; return this; }
        public Builder evaluatedAt(Instant v)         { d.evaluatedAt      = v; return this; }
        public AiModificationDecision build()         { return d; }
    }
}

