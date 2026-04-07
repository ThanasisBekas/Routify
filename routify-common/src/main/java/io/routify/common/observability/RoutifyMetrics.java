package io.routify.common.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.routify.common.client.AmqpServiceClientSupport;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Centralised Micrometer metrics for the Routify platform.
 * All metric names are namespaced under {@code routify.}.
 *
 * <h2>Metric groups</h2>
 * <ul>
 *   <li><b>Gateway</b> — request counters, auth failures, route/filter gauges, request/filter-chain timers</li>
 *   <li><b>Outbox</b>  — {@code routify.outbox.pending} gauge (route-service / cert-vault)</li>
 *   <li><b>RPC</b>     — {@code routify.rpc.latency} timer per exchange (admin-api RabbitMQ calls)</li>
 *   <li><b>DLQ</b>     — {@code routify.dlq.events} counter per DLQ topic</li>
 *   <li><b>Cert</b>    — {@code routify.cert.expiry.days} gauge per logical cert ID</li>
 * </ul>
 */
@Component
public class RoutifyMetrics {

    private final MeterRegistry registry;

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

    // ─── Quota ────────────────────────────────────────────────────────────────

    private final Counter gatewayRequestsQuotaExceeded;

    // ─── Timers ───────────────────────────────────────────────────────────────

    private final Timer gatewayRequestDuration;
    private final Timer filterChainDuration;

    // ─── Gauges ───────────────────────────────────────────────────────────────

    private final AtomicInteger activeRoutes = new AtomicInteger(0);
    private final AtomicInteger loadedFilters = new AtomicInteger(0);

    // ─── Outbox ───────────────────────────────────────────────────────────────

    private final AtomicLong outboxPending = new AtomicLong(0);

    // ─── RPC latency (lazy, per exchange) ─────────────────────────────────────

    private final Map<String, Timer> rpcTimers = new ConcurrentHashMap<>();

    // ─── DLQ counters (lazy, per topic) ───────────────────────────────────────

    private final Map<String, Counter> dlqCounters = new ConcurrentHashMap<>();

    // ─── Cert expiry gauges (lazy, per certId) ────────────────────────────────

    private final Map<String, AtomicLong> certExpiryDays = new ConcurrentHashMap<>();

    // ─── ACME counters (lazy) ────────────────────────────────────────────────

    private final Map<String, Counter> acmeRenewals = new ConcurrentHashMap<>();
    private final Map<String, Counter> acmeFailures = new ConcurrentHashMap<>();

    // ─── Gateway Cluster ─────────────────────────────────────────────────────

    private final AtomicLong gatewayConfigVersion = new AtomicLong(0);

    // ─── Canary Routing ──────────────────────────────────────────────────────

    private final Counter canaryDeployments;
    private final Counter canaryRollbacks;

    // ─── Alerting Engine ──────────────────────────────────────────────────────

    private final Timer   alertEvaluationTimer;
    private final Counter alertsFired;

    // ─── Response Cache ──────────────────────────────────────────────────────

    private final Counter cachePurges;

    public RoutifyMetrics(MeterRegistry registry) {
        this.registry = registry;

        routesCreated      = Counter.builder("routify.routes.created").register(registry);
        routesActivated    = Counter.builder("routify.routes.activated").register(registry);
        routesDeactivated  = Counter.builder("routify.routes.deactivated").register(registry);
        routesDeleted      = Counter.builder("routify.routes.deleted").register(registry);
        filtersCreated     = Counter.builder("routify.filters.created").register(registry);
        gatewayRequests    = Counter.builder("routify.gateway.requests.total").register(registry);
        gatewayRequestsBlocked     = Counter.builder("routify.gateway.requests.blocked").register(registry);
        gatewayRequestsRateLimited = Counter.builder("routify.gateway.requests.rate_limited").register(registry);
        authFailures       = Counter.builder("routify.auth.failures").register(registry);

        gatewayRequestsQuotaExceeded = Counter.builder("routify.gateway.requests.quota_exceeded")
                .description("Requests rejected due to monthly tenant quota exceeded")
                .register(registry);

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

        Gauge.builder("routify.outbox.pending", outboxPending, AtomicLong::get)
                .description("Number of outbox events awaiting publication")
                .register(registry);

        Gauge.builder("routify.gateway.cluster.config-version", gatewayConfigVersion, AtomicLong::get)
                .description("Local config version counter for this gateway instance")
                .register(registry);

        canaryDeployments = Counter.builder("routify.canary.deployments")
                .description("Number of canary route deployments")
                .register(registry);
        canaryRollbacks = Counter.builder("routify.canary.rollbacks")
                .description("Number of canary route rollbacks (manual + auto)")
                .register(registry);

        alertEvaluationTimer = Timer.builder("routify.alerts.evaluation")
                .description("Time spent evaluating alert rules per cycle")
                .register(registry);
        alertsFired = Counter.builder("routify.alerts.fired")
                .description("Number of alert rules that transitioned to FIRING")
                .register(registry);

        cachePurges = Counter.builder("routify.filter.response_cache.purges")
                .description("Number of response cache purge operations")
                .register(registry);
    }

    // ─── Gateway ──────────────────────────────────────────────────────────────

    public void recordRouteCreated()          { routesCreated.increment(); }
    public void recordRouteActivated()        { routesActivated.increment(); /* activeRoutes updated by setActiveRoutes() only */ }
    public void recordRouteDeactivated()      { routesDeactivated.increment(); /* activeRoutes updated by setActiveRoutes() only */ }
    public void recordRouteDeleted()          { routesDeleted.increment(); }
    public void recordFilterCreated()         { filtersCreated.increment(); loadedFilters.incrementAndGet(); }
    public void recordGatewayRequest()        { gatewayRequests.increment(); }
    public void recordGatewayRequestBlocked() { gatewayRequestsBlocked.increment(); }
    public void recordRateLimited()           { gatewayRequestsRateLimited.increment(); }
    public void recordAuthFailure()           { authFailures.increment(); }
    public void recordQuotaExceeded()         { gatewayRequestsQuotaExceeded.increment(); }

    public Timer gatewayRequestDuration() { return gatewayRequestDuration; }
    public Timer filterChainDuration()    { return filterChainDuration; }

    public void setActiveRoutes(int count)   { activeRoutes.set(count); }
    public void setLoadedFilters(int count)  { loadedFilters.set(count); }

    /**
     * Updates the local config version gauge.
     * Called by the gateway's {@code GatewayInstanceRegistry} after each reload.
     *
     * @param version the new local config version
     */
    public void setGatewayConfigVersion(long version) { gatewayConfigVersion.set(version); }

    // ─── Outbox ───────────────────────────────────────────────────────────────

    /**
     * Updates the outbox pending gauge.
     * Called by the outbox poller after each poll cycle.
     *
     * @param count current number of PENDING outbox rows
     */
    public void setOutboxPending(long count) { outboxPending.set(count); }

    // ─── RPC Latency ──────────────────────────────────────────────────────────

    /**
     * Returns (or lazily creates) a timer for RPC calls to the given exchange.
     * The timer is tagged with {@code exchange=<name>} for per-service drill-down.
     *
     * <p>Usage in {@link AmqpServiceClientSupport}:
     * <pre>{@code
     * Timer.Sample sample = Timer.start(registry);
     * Message reply = rabbitTemplate.sendAndReceive(...);
     * sample.stop(metrics.rpcTimer("routify.route-service"));
     * }</pre>
     *
     * @param exchange the RabbitMQ exchange name (e.g. {@code "routify.route-service"})
     */
    public Timer rpcTimer(String exchange) {
        return rpcTimers.computeIfAbsent(exchange, ex ->
                Timer.builder("routify.rpc.latency")
                        .description("RabbitMQ RPC round-trip latency")
                        .tag("exchange", ex)
                        .register(registry));
    }

    // ─── DLQ Events ───────────────────────────────────────────────────────────

    /**
     * Increments the DLQ event counter for the given topic.
     * Called by the DLQ error handler or the audit-service DLQ consumer.
     *
     * @param dlqTopic the DLQ topic name (e.g. {@code "routify.route.events.DLQ"})
     */
    public void recordDlqEvent(String dlqTopic) {
        dlqCounters.computeIfAbsent(dlqTopic, topic ->
                Counter.builder("routify.dlq.events")
                        .description("Records forwarded to Dead-Letter Queue")
                        .tag("topic", topic)
                        .register(registry)
        ).increment();
    }

    // ─── Certificate Expiry ───────────────────────────────────────────────────

    /**
     * Sets the number of days until a certificate expires.
     * Called by the cert-vault or gateway certificate registry on refresh.
     *
     * @param certId   the logical certificate identifier
     * @param daysLeft days until expiry (negative if already expired)
     */
    public void setCertExpiryDays(String certId, long daysLeft) {
        certExpiryDays.computeIfAbsent(certId, id -> {
            var holder = new AtomicLong(daysLeft);
            Gauge.builder("routify.cert.expiry.days", holder, AtomicLong::get)
                    .description("Days until certificate expires (negative = expired)")
                    .tag("certId", id)
                    .register(registry);
            return holder;
        }).set(daysLeft);
    }

    // ─── ACME Certificate Lifecycle ─────────────────────────────────────────────

    /** Increment the ACME renewal success counter. */
    public void recordAcmeRenewal() {
        acmeRenewals.computeIfAbsent("renewals", k ->
                Counter.builder("routify.cert.acme.renewals")
                        .description("Successful ACME certificate renewals")
                        .register(registry)
        ).increment();
    }

    /** Increment the ACME failure counter. */
    public void recordAcmeFailure() {
        acmeFailures.computeIfAbsent("failures", k ->
                Counter.builder("routify.cert.acme.failures")
                        .description("Failed ACME certificate operations (issuance or renewal)")
                        .register(registry)
        ).increment();
    }

    // ─── Canary Routing ──────────────────────────────────────────────────────

    public void recordCanaryDeployment() { canaryDeployments.increment(); }
    public void recordCanaryRollback()   { canaryRollbacks.increment(); }

    // ─── Alerting Engine ──────────────────────────────────────────────────────

    public Timer alertEvaluationTimer()   { return alertEvaluationTimer; }
    public void  recordAlertFired()       { alertsFired.increment(); }

    // ─── Response Cache ──────────────────────────────────────────────────────

    public void recordCachePurge() { cachePurges.increment(); }
}

