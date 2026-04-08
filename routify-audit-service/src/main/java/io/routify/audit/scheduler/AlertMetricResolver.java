package io.routify.audit.scheduler;

import io.routify.audit.domain.AlertRule;
import io.routify.audit.repository.DlqEventRepository;
import io.routify.audit.repository.RequestLogRepository;
import io.routify.common.domain.AlertMetric;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Resolves the current value for an {@link AlertMetric} by querying the appropriate data source.
 *
 * <p>Route-scoped metrics (e.g. ERROR_RATE, P99_LATENCY) use the rule's {@code routeId}
 * when set; otherwise they aggregate across all routes for the tenant.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertMetricResolver {

    private final RequestLogRepository requestLogRepository;
    private final DlqEventRepository   dlqEventRepository;
    private final StringRedisTemplate  redisTemplate;

    /**
     * Resolve the current metric value for the given alert rule.
     *
     * @param rule the alert rule whose metric to evaluate
     * @return the resolved metric value
     */
    public double resolve(AlertRule rule) {
        AlertMetric metric = AlertMetric.valueOf(rule.getMetric());
        UUID tenantId = rule.getTenantId();
        UUID routeId  = rule.getRouteId();
        Instant since = Instant.now().minusSeconds((long) rule.getWindowMinutes() * 60);

        return switch (metric) {
            case ERROR_RATE        -> requestLogRepository.getErrorRate(tenantId, routeId, since);
            case P99_LATENCY       -> requestLogRepository.getP99Latency(tenantId, routeId, since);
            case DLQ_DEPTH         -> dlqEventRepository.countByFailedAtAfter(since);
            case CERT_EXPIRY_DAYS  -> resolveCertExpiry();
            case QUOTA_USAGE       -> resolveQuotaUsage(tenantId);
            case SLO_BUDGET        -> 0.0; // SLO budget resolution requires route-service data; placeholder
            case REQUEST_VOLUME    -> requestLogRepository.getRequestVolume(tenantId, routeId, since, rule.getWindowMinutes());
            case AUTH_FAILURE_RATE -> requestLogRepository.getAuthFailureRate(tenantId, routeId, since);
        };
    }

    /**
     * Resolve cert expiry by checking Redis or returning a safe default.
     * In a full implementation this would RPC to cert-vault; for now we return
     * a large value (no alert) unless cert data is cached locally.
     */
    private double resolveCertExpiry() {
        // Placeholder — cert-vault RPC would be called here.
        // For safety, return 365 (no imminent expiry) so alerts don't false-fire.
        return 365.0;
    }

    /**
     * Resolve quota usage as a percentage of the tenant's monthly request quota consumed.
     */
    private double resolveQuotaUsage(UUID tenantId) {
        try {
            String key = "routify:quota:" + tenantId + ":" + YearMonth.now(ZoneOffset.UTC);
            String val = redisTemplate.opsForValue().get(key);
            if (val == null) return 0.0;

            long currentCount = Long.parseLong(val);
            // We can't know the plan limit here without tenant context.
            // Return the raw count — rules should set threshold as an absolute count,
            // or a separate enrichment step can convert to percentage.
            // For simplicity, return the count as-is (operators set threshold accordingly).
            return currentCount;
        } catch (Exception e) {
            log.warn("Failed to resolve quota usage for tenant {}: {}", tenantId, e.getMessage());
            return 0.0;
        }
    }
}

