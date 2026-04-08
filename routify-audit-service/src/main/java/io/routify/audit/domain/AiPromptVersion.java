package io.routify.audit.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Tracks prompt versions for AI filter policies.
 *
 * <p>Each AI filter can have multiple prompt versions: exactly one ACTIVE
 * (used for live traffic), zero or more DRAFTs (being iterated), and any
 * number of ARCHIVED (historical, read-only).
 *
 * <p>Table: {@code routify_audit.ai_prompt_version}.
 * Lifecycle: DRAFT → ACTIVE ↔ ARCHIVED.
 */
@Entity
@Table(
    name = "ai_prompt_version",
    schema = "routify_audit",
    uniqueConstraints = @UniqueConstraint(columnNames = {"filter_id", "version"}),
    indexes = {
        @Index(name = "idx_prompt_version_filter", columnList = "filter_id, status"),
        @Index(name = "idx_prompt_version_tenant", columnList = "tenant_id")
    }
)
public class AiPromptVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "filter_id", nullable = false, updatable = false)
    private UUID filterId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "version", nullable = false, updatable = false)
    private int version;

    @Column(name = "prompt_text", columnDefinition = "text", nullable = false)
    private String promptText;

    @Column(name = "description", length = 500)
    private String description;

    /** DRAFT | ACTIVE | ARCHIVED */
    @Column(name = "status", length = 20, nullable = false)
    private String status;

    /** Accuracy percentage: correct_count / total_decisions * 100. NULL until labels exist. */
    @Column(name = "accuracy_score", precision = 5, scale = 2)
    private BigDecimal accuracyScore;

    @Column(name = "total_decisions", nullable = false)
    private int totalDecisions;

    @Column(name = "correct_count", nullable = false)
    private int correctCount;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    protected AiPromptVersion() {}

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = "DRAFT";
    }

    // ─── Getters ─────────────────────────────────────────────────────────────

    public UUID       getId()             { return id; }
    public UUID       getFilterId()       { return filterId; }
    public UUID       getTenantId()       { return tenantId; }
    public int        getVersion()        { return version; }
    public String     getPromptText()     { return promptText; }
    public String     getDescription()    { return description; }
    public String     getStatus()         { return status; }
    public BigDecimal getAccuracyScore()  { return accuracyScore; }
    public int        getTotalDecisions() { return totalDecisions; }
    public int        getCorrectCount()   { return correctCount; }
    public String     getCreatedBy()      { return createdBy; }
    public Instant    getCreatedAt()      { return createdAt; }
    public Instant    getActivatedAt()    { return activatedAt; }
    public Instant    getArchivedAt()     { return archivedAt; }

    // ─── Setters (for mutable fields) ────────────────────────────────────────

    public void setStatus(String status)               { this.status = status; }
    public void setAccuracyScore(BigDecimal score)     { this.accuracyScore = score; }
    public void setTotalDecisions(int total)           { this.totalDecisions = total; }
    public void setCorrectCount(int count)             { this.correctCount = count; }
    public void setActivatedAt(Instant activatedAt)    { this.activatedAt = activatedAt; }
    public void setArchivedAt(Instant archivedAt)      { this.archivedAt = archivedAt; }

    // ─── Builder ──────────────────────────────────────────────────────────────

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final AiPromptVersion v = new AiPromptVersion();

        public Builder filterId(UUID val)          { v.filterId = val; return this; }
        public Builder tenantId(UUID val)          { v.tenantId = val; return this; }
        public Builder version(int val)            { v.version = val; return this; }
        public Builder promptText(String val)      { v.promptText = val; return this; }
        public Builder description(String val)     { v.description = val; return this; }
        public Builder status(String val)          { v.status = val; return this; }
        public Builder accuracyScore(BigDecimal v2){ v.accuracyScore = v2; return this; }
        public Builder totalDecisions(int val)     { v.totalDecisions = val; return this; }
        public Builder correctCount(int val)       { v.correctCount = val; return this; }
        public Builder createdBy(String val)       { v.createdBy = val; return this; }
        public Builder createdAt(Instant val)      { v.createdAt = val; return this; }
        public Builder activatedAt(Instant val)    { v.activatedAt = val; return this; }
        public Builder archivedAt(Instant val)     { v.archivedAt = val; return this; }
        public AiPromptVersion build()             { return v; }
    }
}

