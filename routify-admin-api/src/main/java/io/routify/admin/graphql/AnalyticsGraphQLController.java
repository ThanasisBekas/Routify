package io.routify.admin.graphql;

import io.routify.admin.client.AuditMessagingClient;
import io.routify.admin.client.CertVaultMessagingClient;
import io.routify.admin.client.IdentityMessagingClient;
import io.routify.common.event.QueryResponse;
import io.routify.common.exception.RoutifyException;
import graphql.schema.DataFetchingEnvironment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * GraphQL controller for the Analytics API (Initiative 13).
 *
 * <p>Exposes composable analytics queries at {@code /api/v1/admin/graphql}.
 * All data is sourced from existing RabbitMQ messaging clients — no new backend
 * data sources are introduced. Tenant isolation is enforced via the
 * {@link GraphQLSecurityInterceptor} which injects the authenticated tenant into
 * the GraphQL context.
 *
 * <h2>Query mapping</h2>
 * <ul>
 *   <li>{@code routeAnalytics} → audit-service time-series + route health stats</li>
 *   <li>{@code tenantUsage} → audit-service usage current + history</li>
 *   <li>{@code aiFilterAnalytics} → audit-service AI filter stats</li>
 *   <li>{@code certExpiryReport} → cert-vault stats + active certs list</li>
 *   <li>{@code auditTimeline} → audit-service event log</li>
 * </ul>
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class AnalyticsGraphQLController {

    private final AuditMessagingClient auditMessagingClient;
    private final CertVaultMessagingClient certVaultMessagingClient;
    private final IdentityMessagingClient identityMessagingClient;

    // ─── Route Analytics ──────────────────────────────────────────────────────

    @QueryMapping
    public Map<String, Object> routeAnalytics(
            @Argument String tenantId,
            @Argument String routeId,
            @Argument Instant from,
            @Argument Instant to,
            @Argument String granularity,
            DataFetchingEnvironment env) {

        UUID tid = validateTenantAccess(env, tenantId);
        UUID rid = routeId != null ? UUID.fromString(routeId) : null;

        // Determine the window string for the audit-service query
        long hours = ChronoUnit.HOURS.between(from, to);
        String window = hours <= 1 ? "1h" : hours <= 24 ? "24h" : "7d";

        // Fetch route health data from audit-service
        var healthResponse = auditMessagingClient.queryRouteHealth(tid, window);

        // Build per-route metrics with time-series bucketing
        List<Map<String, Object>> routeMetrics = new ArrayList<>();
        long totalRequests = 0;
        long totalErrors = 0;
        double sumLatency = 0;
        double sumP50 = 0;
        double sumP95 = 0;
        double sumP99 = 0;
        int routeCount = 0;

        for (var entry : healthResponse.routes()) {
            // If a specific routeId was requested, filter to that route
            if (rid != null && !rid.equals(entry.routeId())) {
                continue;
            }

            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("routeId", entry.routeId().toString());
            rm.put("routeName", entry.routeName());

            // Build time-series buckets (single bucket for aggregate from route health)
            Map<String, Object> bucket = new LinkedHashMap<>();
            bucket.put("timestamp", from.toString());
            bucket.put("requestCount", entry.totalRequests());
            bucket.put("errorCount", entry.errorCount());
            bucket.put("errorRate", entry.errorRate());
            bucket.put("p50LatencyMs", entry.p50LatencyMs());
            bucket.put("p95LatencyMs", entry.p95LatencyMs());
            bucket.put("p99LatencyMs", entry.p99LatencyMs());
            bucket.put("avgLatencyMs", entry.avgLatencyMs());

            // Status codes
            List<Map<String, Object>> statusCodes = new ArrayList<>();
            if (entry.statusCodeDistribution() != null) {
                entry.statusCodeDistribution().forEach((code, count) -> {
                    Map<String, Object> sc = new LinkedHashMap<>();
                    sc.put("code", code);
                    sc.put("count", count);
                    statusCodes.add(sc);
                });
            }
            bucket.put("statusCodes", statusCodes);
            rm.put("timeSeries", List.of(bucket));

            // Aggregate
            Map<String, Object> agg = new LinkedHashMap<>();
            agg.put("totalRequests", entry.totalRequests());
            agg.put("totalErrors", entry.errorCount());
            agg.put("errorRate", entry.errorRate());
            agg.put("avgLatencyMs", entry.avgLatencyMs());
            agg.put("p50LatencyMs", entry.p50LatencyMs());
            agg.put("p95LatencyMs", entry.p95LatencyMs());
            agg.put("p99LatencyMs", entry.p99LatencyMs());
            rm.put("aggregate", agg);

            routeMetrics.add(rm);

            totalRequests += entry.totalRequests();
            totalErrors += entry.errorCount();
            sumLatency += entry.avgLatencyMs() * entry.totalRequests();
            sumP50 += entry.p50LatencyMs();
            sumP95 += entry.p95LatencyMs();
            sumP99 += entry.p99LatencyMs();
            routeCount++;
        }

        // Totals
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("totalRequests", totalRequests);
        totals.put("totalErrors", totalErrors);
        totals.put("errorRate", totalRequests > 0 ? (double) totalErrors / totalRequests : 0.0);
        totals.put("avgLatencyMs", totalRequests > 0 ? sumLatency / totalRequests : 0.0);
        totals.put("p50LatencyMs", routeCount > 0 ? sumP50 / routeCount : 0.0);
        totals.put("p95LatencyMs", routeCount > 0 ? sumP95 / routeCount : 0.0);
        totals.put("p99LatencyMs", routeCount > 0 ? sumP99 / routeCount : 0.0);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("routes", routeMetrics);
        result.put("totals", totals);
        return result;
    }

    // ─── Tenant Usage ──────────────────────────────────────────────────────────

    @QueryMapping
    public Map<String, Object> tenantUsage(
            @Argument String tenantId,
            @Argument Instant from,
            @Argument Instant to,
            DataFetchingEnvironment env) {

        UUID tid = validateTenantAccess(env, tenantId);

        // Days between from and to
        int days = (int) Math.max(1, ChronoUnit.DAYS.between(from, to));

        // Fetch usage history from audit-service
        var history = auditMessagingClient.queryUsageHistory(tid, days);

        // Fetch tenant detail for plan info
        var tenantDetail = identityMessagingClient.getTenant(tid);
        String planName = tenantDetail != null ? tenantDetail.plan().name() : "FREE";
        int maxRoutes = tenantDetail != null ? tenantDetail.plan().maxRoutes() : 5;
        int maxFilters = tenantDetail != null ? tenantDetail.plan().maxFilters() : 10;
        int monthlyQuota = tenantDetail != null ? tenantDetail.plan().monthlyRequestQuota() : 10_000;

        // Compute current usage from the latest daily entry
        int usedRoutes = 0;
        int usedFilters = 0;
        long usedRequests = 0;
        if (!history.entries().isEmpty()) {
            var latest = history.entries().getFirst();
            usedRoutes = latest.routeCount();
            usedFilters = latest.filterCount();
            // Sum all request counts in the period
            usedRequests = history.entries().stream()
                    .mapToLong(QueryResponse.UsageHistoryResult.DailyUsage::requestCount).sum();
        }

        Map<String, Object> routes = new LinkedHashMap<>();
        routes.put("used", usedRoutes);
        routes.put("limit", maxRoutes);
        routes.put("percentage", maxRoutes > 0 ? (float) usedRoutes / maxRoutes * 100 : 0.0f);

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("used", usedFilters);
        filters.put("limit", maxFilters);
        filters.put("percentage", maxFilters > 0 ? (float) usedFilters / maxFilters * 100 : 0.0f);

        Map<String, Object> requests = new LinkedHashMap<>();
        requests.put("used", (int) Math.min(usedRequests, Integer.MAX_VALUE));
        requests.put("limit", monthlyQuota);
        requests.put("percentage", monthlyQuota > 0 ? (float) usedRequests / monthlyQuota * 100 : 0.0f);

        List<Map<String, Object>> dailyUsage = history.entries().stream().map(e -> {
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", e.date());
            day.put("requestCount", e.requestCount());
            day.put("errorCount", e.errorCount());
            day.put("routeCount", e.routeCount());
            return day;
        }).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenantId", tid.toString());
        result.put("plan", planName);
        result.put("routes", routes);
        result.put("filters", filters);
        result.put("requests", requests);
        result.put("dailyUsage", dailyUsage);
        return result;
    }

    // ─── AI Filter Analytics ───────────────────────────────────────────────────

    @QueryMapping
    public Map<String, Object> aiFilterAnalytics(
            @Argument String tenantId,
            @Argument String filterId,
            @Argument Instant from,
            @Argument Instant to,
            @Argument String granularity,
            DataFetchingEnvironment env) {

        UUID tid = validateTenantAccess(env, tenantId);
        UUID fid = filterId != null ? UUID.fromString(filterId) : null;

        // Fetch AI filter stats from audit-service
        var stats = auditMessagingClient.queryAiFilterStats(
                tid, fid, from.toString(), to.toString());

        Map<String, Object> filterMetrics = new LinkedHashMap<>();
        filterMetrics.put("filterId", fid != null ? fid.toString() : "all");
        filterMetrics.put("filterName", fid != null ? filterId : "All Filters");
        filterMetrics.put("totalDecisions", stats.totalDecisions());
        filterMetrics.put("allowCount", stats.allowCount());
        filterMetrics.put("blockCount", stats.blockCount());
        filterMetrics.put("flagCount", stats.flagCount());
        filterMetrics.put("avgLatencyMs", stats.avgLatencyMs());
        filterMetrics.put("cacheHitRate", stats.totalDecisions() > 0
                ? (double) stats.cacheHitCount() / stats.totalDecisions() : 0.0);
        filterMetrics.put("accuracyScore", null); // Requires prompt version context
        filterMetrics.put("timeSeries", List.of()); // Populated by time-series query (Step 4)

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("filters", List.of(filterMetrics));
        return result;
    }

    // ─── Cert Expiry Report ───────────────────────────────────────────────────

    @QueryMapping
    public Map<String, Object> certExpiryReport(
            @Argument String tenantId,
            DataFetchingEnvironment env) {

        UUID tid = validateTenantAccess(env, tenantId);

        // Fetch cert stats from cert-vault
        var certStats = certVaultMessagingClient.getCertVaultStats(tid);
        var activeCerts = certVaultMessagingClient.listActiveCertificates(tid);

        int totalCerts = (int) certStats.total();
        int expiringSoon = (int) certStats.expiringSoon();
        int expired = 0;

        List<Map<String, Object>> certs = new ArrayList<>();
        if (activeCerts != null && activeCerts.items() != null) {
            for (var cert : activeCerts.items()) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("certId", cert.id().toString());
                c.put("alias", cert.alias());

                int daysUntilExpiry = 0;
                if (cert.notAfter() != null) {
                    daysUntilExpiry = (int) ChronoUnit.DAYS.between(Instant.now(), cert.notAfter());
                    c.put("notAfter", cert.notAfter().toString());
                } else {
                    c.put("notAfter", null);
                }
                c.put("daysUntilExpiry", daysUntilExpiry);
                c.put("status", cert.status());
                c.put("autoRenew", false); // ACME auto-renew status requires order lookup

                if (daysUntilExpiry < 0) {
                    expired++;
                }

                certs.add(c);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCerts", totalCerts);
        result.put("expiringSoon", expiringSoon);
        result.put("expired", expired);
        result.put("certs", certs);
        return result;
    }

    // ─── Audit Timeline ───────────────────────────────────────────────────────

    @QueryMapping
    public List<Map<String, Object>> auditTimeline(
            @Argument String tenantId,
            @Argument String aggregateType,
            @Argument Instant from,
            @Argument Instant to,
            @Argument Integer limit,
            DataFetchingEnvironment env) {

        UUID tid = validateTenantAccess(env, tenantId);
        int pageSize = limit != null && limit > 0 ? Math.min(limit, 100) : 100;

        var eventsPage = auditMessagingClient.queryAuditEvents(
                tid, null, aggregateType, null,
                from.toString(), to.toString(), 0, pageSize);

        return eventsPage.content().stream().map(e -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("eventId", e.eventId().toString());
            entry.put("eventType", e.eventType());
            entry.put("aggregateType", e.aggregateType());
            entry.put("aggregateId", e.aggregateId());
            entry.put("actorId", e.actorId());
            entry.put("occurredAt", e.occurredAt().toString());
            return entry;
        }).toList();
    }

    // ─── Security Helpers ──────────────────────────────────────────────────────

    /**
     * Validates that the current user has access to the requested tenant.
     * SUPER_ADMIN can query any tenant; other roles can only query their own.
     */
    private UUID validateTenantAccess(DataFetchingEnvironment env, String requestedTenantId) {
        UUID requestedTid = UUID.fromString(requestedTenantId);
        boolean isSuperAdmin = env.getGraphQlContext().getOrDefault("isSuperAdmin", false);

        if (!isSuperAdmin) {
            String authTenantId = env.getGraphQlContext().getOrDefault("authenticatedTenantId", "");
            if (!requestedTenantId.equals(authTenantId)) {
                throw new RoutifyException.Forbidden(
                        "Access denied: cannot query analytics for tenant " + requestedTenantId);
            }
        }

        return requestedTid;
    }
}

