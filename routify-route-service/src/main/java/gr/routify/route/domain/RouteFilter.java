package gr.routify.route.domain;

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
        filter.incrementUsage();
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
        filterDefinition.decrementUsage();
    }
}

