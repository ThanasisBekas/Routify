package io.routify.common.domain;

/**
 * Tenant subscription plans controlling quota and features.
 */
public enum TenantPlan {
    FREE(10, 5, 1_000),
    STARTER(50, 20, 10_000),
    PRO(200, 100, 100_000),
    ENTERPRISE(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);

    private final int maxRoutes;
    private final int maxFilters;
    private final int monthlyRequestQuota;

    TenantPlan(int maxRoutes, int maxFilters, int monthlyRequestQuota) {
        this.maxRoutes = maxRoutes;
        this.maxFilters = maxFilters;
        this.monthlyRequestQuota = monthlyRequestQuota;
    }

    public int maxRoutes()              { return maxRoutes; }
    public int maxFilters()             { return maxFilters; }
    public int monthlyRequestQuota()    { return monthlyRequestQuota; }
}

