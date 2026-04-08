package io.routify.common.domain;

/**
 * Tenant subscription plans controlling quota and features.
 */
public enum TenantPlan {
    FREE(10, 5, 1_000, 5L * 1024 * 1024),            // 5 MB
    STARTER(50, 20, 10_000, 10L * 1024 * 1024),       // 10 MB
    PRO(200, 100, 100_000, 50L * 1024 * 1024),        // 50 MB
    ENTERPRISE(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 500L * 1024 * 1024); // 500 MB

    private final int maxRoutes;
    private final int maxFilters;
    private final int monthlyRequestQuota;
    private final long maxRequestBodySize;

    TenantPlan(int maxRoutes, int maxFilters, int monthlyRequestQuota, long maxRequestBodySize) {
        this.maxRoutes = maxRoutes;
        this.maxFilters = maxFilters;
        this.monthlyRequestQuota = monthlyRequestQuota;
        this.maxRequestBodySize = maxRequestBodySize;
    }

    public int maxRoutes()              { return maxRoutes; }
    public int maxFilters()             { return maxFilters; }
    public int monthlyRequestQuota()    { return monthlyRequestQuota; }
    /** Maximum request body size in bytes for this plan tier. */
    public long maxRequestBodySize()    { return maxRequestBodySize; }
}

