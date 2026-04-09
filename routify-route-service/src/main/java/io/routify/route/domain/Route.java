package io.routify.route.domain;

import io.routify.common.domain.RouteEnvironment;
import io.routify.common.domain.RouteStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.*;

/**
 * Core domain entity representing a Gateway Route.
 *
 * <p>A Route defines:
 * <ul>
 *   <li>Where it matches: path pattern + HTTP method(s)</li>
 *   <li>Where it goes: upstream URI</li>
 *   <li>How it's secured/modified: ordered filter chain</li>
 *   <li>Lifecycle: DRAFT → ACTIVE (hot-reloaded into gateway) → DISABLED</li>
 * </ul>
 *
 * <p>Uses Hibernate's JSONB support for flexible filter configuration storage.
 */
@Entity
@Table(
    name = "route",
    schema = "routify"
    // Uniqueness is enforced by a partial unique index (V9 migration) that excludes ARCHIVED rows,
    // allowing re-creation of STAGING routes after promotion archives the previous one.
)
public class Route {

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

    // ─── Matching ─────────────────────────────────────────────────────────────

    /**
     * URL path pattern. Supports Ant-style patterns (e.g. /api/v1/orders/**) and
     * URI template variables (e.g. /users/{id}). Stored as plain string.
     */
    @Column(name = "path_pattern", nullable = false, length = 500)
    private String pathPattern;

    /**
     * Comma-separated list of HTTP methods (GET,POST,PUT,DELETE,PATCH,*).
     * "*" matches all methods.
     */
    @Column(nullable = false, length = 100)
    private String methods;

    // ─── Upstream ─────────────────────────────────────────────────────────────

    /**
     * Upstream target URI. Supports:
     * - http(s)://host:port — direct
     * - lb://service-name — load-balanced (Spring Cloud LoadBalancer)
     */
    @Column(name = "upstream_uri", nullable = false, length = 500)
    private String upstreamUri;

    /**
     * Optional path to strip/replace when forwarding to upstream.
     * E.g. strip prefix /api/v1 → forward /orders/{id}
     */
    @Column(name = "strip_prefix", length = 200)
    private String stripPrefix;

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RouteStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RouteEnvironment environment;

    /** Monotonically increasing version — incremented on every activation */
    @Column(nullable = false)
    private Integer version;

    @Column(name = "activated_at")
    private Instant activatedAt;

    // ─── Filter Chain ─────────────────────────────────────────────────────────

    /**
     * Ordered list of filter attachments.
     * Filters execute in {@code order} sequence (lowest first).
     */
    @OneToMany(
        mappedBy = "route",
        cascade = CascadeType.ALL,
        orphanRemoval = true,
        fetch = FetchType.LAZY
    )
    @OrderBy("filterOrder ASC")
    private List<RouteFilter> filters = new ArrayList<>();

    // ─── Advanced Configuration ───────────────────────────────────────────────

    /**
     * Miscellaneous route-level config stored as JSONB.
     * E.g.: { "timeout": 30000, "retries": 3, "preserveHostHeader": true }
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extra_config", columnDefinition = "jsonb")
    private Map<String, Object> extraConfig = new HashMap<>();

    // ─── Canary Routing ────────────────────────────────────────────────────────

    /** Traffic weight percentage (0–100). Default 100 means all traffic goes to this route. */
    @Column(name = "traffic_weight", nullable = false)
    private int trafficWeight = 100;

    /** FK to the canary sibling route, set on the primary route when a canary is deployed. */
    @Column(name = "canary_route_id")
    private UUID canaryRouteId;

    /** Error rate % threshold above which the canary should be auto-rolled back. */
    @Column(name = "canary_auto_rollback_threshold")
    private java.math.BigDecimal canaryAutoRollbackThreshold;

    /** True if this route is a canary sibling (not the primary). Excluded from path uniqueness constraint. */
    @Column(name = "is_canary", nullable = false)
    private boolean canary;

    // ─── Metadata ─────────────────────────────────────────────────────────────

    /** User who created this route */
    @Column(name = "created_by", length = 255)
    private String createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    // ─── Constructors ─────────────────────────────────────────────────────────

    protected Route() {}

    private Route(Builder builder) {
        this.tenantId    = Objects.requireNonNull(builder.tenantId, "tenantId");
        this.name        = Objects.requireNonNull(builder.name, "name");
        this.description = builder.description;
        this.pathPattern = Objects.requireNonNull(builder.pathPattern, "pathPattern");
        this.methods     = builder.methods != null ? builder.methods : "*";
        this.upstreamUri = Objects.requireNonNull(builder.upstreamUri, "upstreamUri");
        this.stripPrefix = builder.stripPrefix;
        this.status      = RouteStatus.DRAFT;
        this.environment = builder.environment != null ? builder.environment : RouteEnvironment.PRODUCTION;
        this.version     = 1;
        this.createdBy   = builder.createdBy;
        this.extraConfig = builder.extraConfig != null ? builder.extraConfig : new HashMap<>();
    }

    // ─── Domain Behaviour ─────────────────────────────────────────────────────

    /**
     * Activates this route for live gateway traffic.
     * Validates that the route is in DRAFT or DISABLED state.
     * Increments version — consumers use this to detect stale cached routes.
     *
     * @throws IllegalStateException if the route is already ACTIVE or ARCHIVED
     */
    public void activate() {
        if (status == RouteStatus.ACTIVE) {
            throw new IllegalStateException("Route '%s' is already ACTIVE".formatted(name));
        }
        if (status == RouteStatus.ARCHIVED) {
            throw new IllegalStateException(
                    "Route '%s' is ARCHIVED and cannot be reactivated".formatted(name));
        }
        this.status = RouteStatus.ACTIVE;
        this.version = this.version + 1;
        this.activatedAt = Instant.now();
    }

    /**
     * Deactivates this route — removes it from gateway routing table.
     * Only ACTIVE routes can be deactivated. DRAFT and ARCHIVED routes
     * must use other transitions (activate or archive).
     *
     * @throws IllegalStateException if the route is not ACTIVE
     */
    public void deactivate() {
        if (status != RouteStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Route '%s' cannot be deactivated (current: %s). Only ACTIVE routes can be deactivated."
                            .formatted(name, status));
        }
        this.status = RouteStatus.DISABLED;
        this.version = this.version + 1;
    }

    /** Marks this route as archived — soft delete. Cannot be reversed. */
    public void archive() {
        this.status = RouteStatus.ARCHIVED;
    }

    /**
     * Attaches a filter to this route at the given order position.
     * The filter must be globally reusable OR belong to this tenant.
     */
    public void attachFilter(FilterDefinition filter, int order, String phase) {
        boolean alreadyAttached = filters.stream()
                .anyMatch(f -> f.getFilterDefinition().getId().equals(filter.getId()));
        if (alreadyAttached) {
            throw new IllegalArgumentException(
                    "Filter '%s' is already attached to route '%s'".formatted(filter.getName(), name));
        }
        filters.add(new RouteFilter(this, filter, order, phase));
    }

    /** Detaches a filter from this route by filter definition ID. */
    public void detachFilter(UUID filterId) {
        boolean removed = filters.removeIf(
                f -> f.getFilterDefinition().getId().equals(filterId));
        if (!removed) {
            throw new IllegalArgumentException(
                    "Filter '%s' is not attached to route '%s'".formatted(filterId, name));
        }
    }

    /** Returns true if this route is currently serving traffic */
    public boolean isActive() { return status == RouteStatus.ACTIVE; }

    /**
     * Increments the route version without changing status.
     * Used when an already-ACTIVE production route is updated via promotion
     * so the gateway detects the change and hot-reloads.
     */
    public void incrementVersion() {
        this.version = this.version + 1;
    }

    // ─── Getters ──────────────────────────────────────────────────────────────

    public UUID getId()            { return id; }
    public UUID getTenantId()      { return tenantId; }
    public String getName()        { return name; }
    public String getDescription() { return description; }
    public String getPathPattern() { return pathPattern; }
    public String getMethods()     { return methods; }
    public String getUpstreamUri() { return upstreamUri; }
    public String getStripPrefix() { return stripPrefix; }
    public RouteStatus getStatus() { return status; }
    public RouteEnvironment getEnvironment() { return environment; }
    public Integer getVersion()    { return version; }
    public Instant getActivatedAt(){ return activatedAt; }
    public List<RouteFilter> getFilters() { return Collections.unmodifiableList(filters); }
    public Map<String, Object> getExtraConfig() { return Collections.unmodifiableMap(extraConfig); }
    public String getCreatedBy()   { return createdBy; }
    public Instant getCreatedAt()  { return createdAt; }
    public Instant getUpdatedAt()  { return updatedAt; }
    public int getTrafficWeight()  { return trafficWeight; }
    public UUID getCanaryRouteId() { return canaryRouteId; }
    public java.math.BigDecimal getCanaryAutoRollbackThreshold() { return canaryAutoRollbackThreshold; }
    public boolean isCanary()     { return canary; }

    // ─── Setters (package-private for service layer) ──────────────────────────

    public void setName(String name)               { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    public void setPathPattern(String pathPattern) { this.pathPattern = pathPattern; }
    public void setMethods(String methods)         { this.methods = methods; }
    public void setUpstreamUri(String upstreamUri) { this.upstreamUri = upstreamUri; }
    public void setStripPrefix(String stripPrefix) { this.stripPrefix = stripPrefix; }
    public void setExtraConfig(Map<String, Object> extraConfig) { this.extraConfig = extraConfig; }
    public void setEnvironment(RouteEnvironment environment) { this.environment = environment; }
    public void setTrafficWeight(int trafficWeight)       { this.trafficWeight = trafficWeight; }
    public void setCanaryRouteId(UUID canaryRouteId)      { this.canaryRouteId = canaryRouteId; }
    public void setCanaryAutoRollbackThreshold(java.math.BigDecimal threshold) { this.canaryAutoRollbackThreshold = threshold; }
    public void setCanary(boolean canary)                { this.canary = canary; }

    // ─── Builder ──────────────────────────────────────────────────────────────

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private UUID tenantId;
        private String name;
        private String description;
        private String pathPattern;
        private String methods;
        private String upstreamUri;
        private String stripPrefix;
        private String createdBy;
        private Map<String, Object> extraConfig;
        private RouteEnvironment environment;

        public Builder tenantId(UUID tenantId)          { this.tenantId = tenantId; return this; }
        public Builder name(String name)                { this.name = name; return this; }
        public Builder description(String description)  { this.description = description; return this; }
        public Builder pathPattern(String pathPattern)  { this.pathPattern = pathPattern; return this; }
        public Builder methods(String methods)          { this.methods = methods; return this; }
        public Builder upstreamUri(String upstreamUri)  { this.upstreamUri = upstreamUri; return this; }
        public Builder stripPrefix(String stripPrefix)  { this.stripPrefix = stripPrefix; return this; }
        public Builder createdBy(String createdBy)      { this.createdBy = createdBy; return this; }
        public Builder extraConfig(Map<String, Object> cfg) { this.extraConfig = cfg; return this; }
        public Builder environment(RouteEnvironment env){ this.environment = env; return this; }
        public Route build()                            { return new Route(this); }
    }
}
