package io.routify.route.domain;

import io.routify.common.domain.FilterType;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.*;

/**
 * A reusable filter definition that can be attached to one or more routes.
 *
 * <p>Stores the filter type (e.g. RATE_LIMIT_TOKEN_BUCKET, AUTH_JWT) and a
 * JSON configuration blob specific to that type. The gateway reads these
 * definitions and constructs the filter chain at request time.
 *
 * <p>Design principle: FilterDefinitions are immutable once activated on a route.
 * To change a filter's config, create a new version. This prevents accidental
 * breaking changes to live routes.
 */
@Entity
@Table(
    name = "filter_definition",
    schema = "routify",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_filter_name_tenant",
        columnNames = {"name", "tenant_id"}
    )
)
public class FilterDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private FilterType filterType;

    /**
     * Type-specific configuration stored as JSONB.
     *
     * <p>Examples by filter type:
     * <pre>
     * AUTH_JWT:
     *   { "issuer": "https://auth.example.com", "audience": "api", "algorithm": "RS256" }
     *
     * RATE_LIMIT_TOKEN_BUCKET:
     *   { "replenishRate": 100, "burstCapacity": 200, "keyResolver": "IP" }
     *
     * REQUEST_HEADER_MODIFY:
     *   { "set": {"X-Tenant": "my-tenant"}, "remove": ["X-Internal-Id"] }
     *
     * PATH_REWRITE:
     *   { "regexp": "/api/v1/(?<segment>.*)", "replacement": "/${segment}" }
     *
     * BODY_JOLT_TRANSFORM:
     *   { "spec": [...jolt spec array...] }
     *
     * VALIDATE_JSON_SCHEMA:
     *   { "schema": {...json-schema object...} }
     *
     * CIRCUIT_BREAKER:
     *   { "name": "my-cb", "fallbackUri": "/fallback/503",
     *     "slidingWindowSize": 10, "failureRateThreshold": 50 }
     *
     * API_VERSIONING:
     *   { "type": "HEADER", "header": "X-API-Version",
     *     "routes": { "v1": "http://api-v1:8080", "v2": "http://api-v2:8080" } }
     *
     * CERT_ROTATION:
     *   { "keystoreRef": "vault://secret/certs/my-cert",
     *     "rotationIntervalDays": 90, "autoRotate": true }
     * </pre>
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> config;

    /** Whether this filter is system-managed (cannot be deleted by tenants) */
    @Column(nullable = false)
    private Boolean systemManaged;

    /** Whether this filter definition is enabled */
    @Column(nullable = false)
    private Boolean enabled;

    /**
     * Optional reference to a gateway configuration entry that provides the authoritative
     * settings for this filter (e.g. an Auth Provider, Rate Limit Policy, etc.).
     *
     * <p>Structure:
     * <pre>
     * {
     *   "refType": "AUTH_PROVIDER" | "RATE_LIMIT_POLICY" | "CIRCUIT_BREAKER_DEFAULTS" |
     *              "RESILIENCE_DEFAULTS" | "TLS_SOURCE" | "DOWNSTREAM_CREDENTIAL",
     *   "refId":   "the-id-of-the-gateway-config-entry",
     *   "refName": "human-readable name (cached for display, not authoritative)"
     * }
     * </pre>
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "gateway_config_ref", columnDefinition = "jsonb")
    private Map<String, Object> gatewayConfigRef;

    /** Number of routes currently using this filter definition */
    @Column(name = "usage_count", nullable = false)
    private Integer usageCount;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    // ─── Constructors ─────────────────────────────────────────────────────────

    protected FilterDefinition() {}

    private FilterDefinition(Builder builder) {
        this.tenantId          = Objects.requireNonNull(builder.tenantId, "tenantId");
        this.name              = Objects.requireNonNull(builder.name, "name");
        this.description       = builder.description;
        this.filterType        = Objects.requireNonNull(builder.filterType, "filterType");
        this.config            = builder.config != null ? builder.config : new HashMap<>();
        this.systemManaged     = builder.systemManaged != null ? builder.systemManaged : false;
        this.enabled           = true;
        this.usageCount        = 0;
        this.createdBy         = builder.createdBy;
        this.gatewayConfigRef  = builder.gatewayConfigRef;
    }

    // ─── Domain Behaviour ─────────────────────────────────────────────────────

    /** Increments usage count when attached to a route */
    public void incrementUsage() { this.usageCount++; }

    /** Decrements usage count when detached from a route */
    public void decrementUsage() {
        if (usageCount > 0) this.usageCount--;
    }

    public boolean isInUse() { return usageCount > 0; }

    public void disable() { this.enabled = false; }
    public void enable()  { this.enabled = true; }

    // ─── Getters / Setters ────────────────────────────────────────────────────

    public UUID getId()               { return id; }
    public UUID getTenantId()         { return tenantId; }
    public String getName()           { return name; }
    public String getDescription()    { return description; }
    public FilterType getFilterType() { return filterType; }
    public Map<String, Object> getConfig() { return Collections.unmodifiableMap(config); }
    public Boolean isSystemManaged()  { return systemManaged; }
    public Boolean isEnabled()        { return enabled; }
    public Integer getUsageCount()    { return usageCount; }
    public String getCreatedBy()      { return createdBy; }
    public Instant getCreatedAt()     { return createdAt; }
    public Instant getUpdatedAt()     { return updatedAt; }
    public Map<String, Object> getGatewayConfigRef()              { return gatewayConfigRef; }

    public void setName(String name)                 { this.name = name; }
    public void setDescription(String description)   { this.description = description; }
    public void setConfig(Map<String, Object> config){ this.config = config; }
    public void setGatewayConfigRef(Map<String, Object> ref)      { this.gatewayConfigRef = ref; }

    // ─── Builder ──────────────────────────────────────────────────────────────

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private UUID tenantId;
        private String name;
        private String description;
        private FilterType filterType;
        private Map<String, Object> config;
        private Boolean systemManaged;
        private String createdBy;
        private Map<String, Object> gatewayConfigRef;

        public Builder tenantId(UUID tenantId)                    { this.tenantId = tenantId; return this; }
        public Builder name(String name)                          { this.name = name; return this; }
        public Builder description(String desc)                   { this.description = desc; return this; }
        public Builder filterType(FilterType type)                { this.filterType = type; return this; }
        public Builder config(Map<String, Object> cfg)            { this.config = cfg; return this; }
        public Builder systemManaged(boolean sm)                  { this.systemManaged = sm; return this; }
        public Builder createdBy(String createdBy)                { this.createdBy = createdBy; return this; }
        public Builder gatewayConfigRef(Map<String, Object> ref)  { this.gatewayConfigRef = ref; return this; }
        public FilterDefinition build()                           { return new FilterDefinition(this); }
    }
}
