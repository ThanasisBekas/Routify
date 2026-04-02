package gr.routify.common.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Centralised Micrometer metrics for the Routify platform.
 * All metric names are namespaced under {@code routify.}.
 */
@Component
public class RoutifyMetrics {

    // ─── Counters ─────────────────────────────────────────────────────────────

    private final Counter routesCreated;
    private final Counter routesActivated;
    private final Counter routesDeactivated;
    private final Counter routesDeleted;
    private final Counter filtersCreated;
    private final Counter gatewayRequests;
    private final Counter gatewayRequestsBlocked;
    private final Counter gatewayRequestsRateLimited;
    private final Counter authFailures;

    // ─── Timers ───────────────────────────────────────────────────────────────

    private final Timer gatewayRequestDuration;
    private final Timer filterChainDuration;

    // ─── Gauges ───────────────────────────────────────────────────────────────

    private final AtomicInteger activeRoutes = new AtomicInteger(0);
    private final AtomicInteger loadedFilters = new AtomicInteger(0);

    public RoutifyMetrics(MeterRegistry registry) {
        routesCreated      = Counter.builder("routify.routes.created").register(registry);
        routesActivated    = Counter.builder("routify.routes.activated").register(registry);
        routesDeactivated  = Counter.builder("routify.routes.deactivated").register(registry);
        routesDeleted      = Counter.builder("routify.routes.deleted").register(registry);
        filtersCreated     = Counter.builder("routify.filters.created").register(registry);
        gatewayRequests    = Counter.builder("routify.gateway.requests.total").register(registry);
        gatewayRequestsBlocked     = Counter.builder("routify.gateway.requests.blocked").register(registry);
        gatewayRequestsRateLimited = Counter.builder("routify.gateway.requests.rate_limited").register(registry);
        authFailures       = Counter.builder("routify.auth.failures").register(registry);

        gatewayRequestDuration = Timer.builder("routify.gateway.request.duration")
                .description("End-to-end gateway request duration")
                .register(registry);
        filterChainDuration = Timer.builder("routify.filter.chain.duration")
                .description("Filter chain execution duration")
                .register(registry);

        Gauge.builder("routify.routes.active", activeRoutes, AtomicInteger::get)
                .description("Number of currently active routes")
                .register(registry);
        Gauge.builder("routify.filters.loaded", loadedFilters, AtomicInteger::get)
                .description("Number of loaded filter definitions")
                .register(registry);
    }

    public void recordRouteCreated()          { routesCreated.increment(); }
    public void recordRouteActivated()        { routesActivated.increment(); /* activeRoutes updated by setActiveRoutes() only */ }
    public void recordRouteDeactivated()      { routesDeactivated.increment(); /* activeRoutes updated by setActiveRoutes() only */ }
    public void recordRouteDeleted()          { routesDeleted.increment(); }
    public void recordFilterCreated()         { filtersCreated.increment(); loadedFilters.incrementAndGet(); }
    public void recordGatewayRequest()        { gatewayRequests.increment(); }
    public void recordGatewayRequestBlocked() { gatewayRequestsBlocked.increment(); }
    public void recordRateLimited()           { gatewayRequestsRateLimited.increment(); }
    public void recordAuthFailure()           { authFailures.increment(); }

    public Timer gatewayRequestDuration() { return gatewayRequestDuration; }
    public Timer filterChainDuration()    { return filterChainDuration; }

    public void setActiveRoutes(int count)   { activeRoutes.set(count); }
    public void setLoadedFilters(int count)  { loadedFilters.set(count); }
}

