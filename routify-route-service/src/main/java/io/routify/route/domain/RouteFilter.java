package io.routify.route.domain;

import jakarta.persistence.*;

import java.util.Objects;
import java.util.UUID;

/**
 * Join entity between Route and FilterDefinition.
 *
 * <p>Represents the ordered filter chain for a route.
 * Filters execute in ascending {@code filterOrder} order during a request.
 *
 * <p>The {@code phase} indicates when in the request lifecycle this filter runs:
 * <ul>
 *   <li>PRE  — before forwarding to upstream (auth, rate limit, request modification)</li>
 *   <li>POST — after receiving upstream response (response modification, logging)</li>
 * </ul>
 *
 * <h3>Usage count (M2 fix)</h3>
 * <p>The previous design called {@code filter.incrementUsage()} / {@code filter.decrementUsage()}
 * directly from the constructor and {@code @PreRemove} — updating an in-memory field then
 * persisting it via dirty-check. Under concurrent transactions both sides of a race would
 * read the same count and produce an incorrect result.
 *
 * <p>The usage counter is now managed exclusively by the service layer via atomic SQL
 * ({@code FilterDefinitionRepository.incrementUsageAtomic()} / {@code decrementUsageAtomic()}).
 * The entity constructor and {@code @PreRemove} no longer touch the counter. The domain
 * guard {@code FilterDefinition.isInUse()} is still available for read checks (it reads the
 * persisted column value loaded at entity hydration time — safe for informational guards).
 */
@Entity
@Table(
    name = "route_filter",
    schema = "routify",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_route_filter",
        columnNames = {"route_id", "filter_definition_id"}
    )
)
public class RouteFilter {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "route_id", nullable = false)
    private Route route;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "filter_definition_id", nullable = false)
    private FilterDefinition filterDefinition;

    /** Execution order — lower numbers run first */
    @Column(name = "filter_order", nullable = false)
    private Integer filterOrder;

    /**
     * Execution phase: "PRE" or "POST".
     * PRE filters run before the upstream call; POST filters run after.
     */
    @Column(nullable = false, length = 10)
    private String phase;

    /** Whether this filter attachment is enabled */
    @Column(nullable = false)
    private Boolean enabled;

    protected RouteFilter() {}

    public RouteFilter(Route route, FilterDefinition filter, int order, String phase) {
        this.route            = Objects.requireNonNull(route);
        this.filterDefinition = Objects.requireNonNull(filter);
        this.filterOrder      = order;
        this.phase            = Objects.requireNonNullElse(phase, "PRE");
        this.enabled          = true;
        // NOTE: Do NOT call filter.incrementUsage() here.
        // Usage count is managed atomically by the service layer via
        // FilterDefinitionRepository.incrementUsageAtomic() — see M2 fix.
    }

    // ─── Getters / Setters ────────────────────────────────────────────────────

    public UUID getId()                          { return id; }
    public Route getRoute()                      { return route; }
    public FilterDefinition getFilterDefinition(){ return filterDefinition; }
    public Integer getFilterOrder()              { return filterOrder; }
    public String getPhase()                     { return phase; }
    public Boolean isEnabled()                   { return enabled; }

    public void setFilterOrder(Integer order)    { this.filterOrder = order; }
    public void setPhase(String phase)           { this.phase = phase; }
    public void setEnabled(Boolean enabled)      { this.enabled = enabled; }

    @PreRemove
    private void onRemove() {
        // NOTE: Do NOT call filterDefinition.decrementUsage() here.
        // Usage count is managed atomically by the service layer via
        // FilterDefinitionRepository.decrementUsageAtomic() — see M2 fix.
    }
}

