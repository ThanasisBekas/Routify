package io.routify.audit.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Daily snapshot of tenant resource usage and request metrics.
 *
 * <p>Populated by {@code UsageSnapshotScheduler} at 00:05 UTC each day.
 * Used by the tenant usage analytics dashboard.
 */
@Entity
@Table(
    name = "tenant_usage_daily",
    schema = "routify_audit",
    uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "date"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TenantUsageDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "date", nullable = false, updatable = false)
    private LocalDate date;

    @Column(name = "route_count", nullable = false)
    private int routeCount;

    @Column(name = "filter_count", nullable = false)
    private int filterCount;

    @Column(name = "request_count", nullable = false)
    private long requestCount;

    @Column(name = "error_count", nullable = false)
    private long errorCount;

    @Column(name = "snapshot_at", nullable = false)
    private Instant snapshotAt;

    public TenantUsageDaily(UUID tenantId, LocalDate date, int routeCount, int filterCount,
                            long requestCount, long errorCount) {
        this.tenantId = tenantId;
        this.date = date;
        this.routeCount = routeCount;
        this.filterCount = filterCount;
        this.requestCount = requestCount;
        this.errorCount = errorCount;
        this.snapshotAt = Instant.now();
    }
}

