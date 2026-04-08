package io.routify.common.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link TenantPlan} enum — validates quota values per plan tier and
 * ensures the enum contract is consistent.
 */
class TenantPlanTest {

    // ─── Quota values per tier ────────────────────────────────────────────────

    @Nested
    @DisplayName("FREE plan quotas")
    class FreePlan {

        @Test
        @DisplayName("maxRoutes = 10")
        void maxRoutes() {
            assertThat(TenantPlan.FREE.maxRoutes()).isEqualTo(10);
        }

        @Test
        @DisplayName("maxFilters = 5")
        void maxFilters() {
            assertThat(TenantPlan.FREE.maxFilters()).isEqualTo(5);
        }

        @Test
        @DisplayName("monthlyRequestQuota = 1,000")
        void monthlyRequestQuota() {
            assertThat(TenantPlan.FREE.monthlyRequestQuota()).isEqualTo(1_000);
        }

        @Test
        @DisplayName("maxRequestBodySize = 5 MB")
        void maxRequestBodySize() {
            assertThat(TenantPlan.FREE.maxRequestBodySize()).isEqualTo(5L * 1024 * 1024);
        }
    }

    @Nested
    @DisplayName("STARTER plan quotas")
    class StarterPlan {

        @Test
        @DisplayName("maxRoutes = 50")
        void maxRoutes() {
            assertThat(TenantPlan.STARTER.maxRoutes()).isEqualTo(50);
        }

        @Test
        @DisplayName("maxFilters = 20")
        void maxFilters() {
            assertThat(TenantPlan.STARTER.maxFilters()).isEqualTo(20);
        }

        @Test
        @DisplayName("monthlyRequestQuota = 10,000")
        void monthlyRequestQuota() {
            assertThat(TenantPlan.STARTER.monthlyRequestQuota()).isEqualTo(10_000);
        }

        @Test
        @DisplayName("maxRequestBodySize = 10 MB")
        void maxRequestBodySize() {
            assertThat(TenantPlan.STARTER.maxRequestBodySize()).isEqualTo(10L * 1024 * 1024);
        }
    }

    @Nested
    @DisplayName("PRO plan quotas")
    class ProPlan {

        @Test
        @DisplayName("maxRoutes = 200")
        void maxRoutes() {
            assertThat(TenantPlan.PRO.maxRoutes()).isEqualTo(200);
        }

        @Test
        @DisplayName("maxFilters = 100")
        void maxFilters() {
            assertThat(TenantPlan.PRO.maxFilters()).isEqualTo(100);
        }

        @Test
        @DisplayName("monthlyRequestQuota = 100,000")
        void monthlyRequestQuota() {
            assertThat(TenantPlan.PRO.monthlyRequestQuota()).isEqualTo(100_000);
        }

        @Test
        @DisplayName("maxRequestBodySize = 50 MB")
        void maxRequestBodySize() {
            assertThat(TenantPlan.PRO.maxRequestBodySize()).isEqualTo(50L * 1024 * 1024);
        }
    }

    @Nested
    @DisplayName("ENTERPRISE plan quotas")
    class EnterprisePlan {

        @Test
        @DisplayName("maxRoutes = Integer.MAX_VALUE (unlimited)")
        void maxRoutes() {
            assertThat(TenantPlan.ENTERPRISE.maxRoutes()).isEqualTo(Integer.MAX_VALUE);
        }

        @Test
        @DisplayName("maxFilters = Integer.MAX_VALUE (unlimited)")
        void maxFilters() {
            assertThat(TenantPlan.ENTERPRISE.maxFilters()).isEqualTo(Integer.MAX_VALUE);
        }

        @Test
        @DisplayName("monthlyRequestQuota = Integer.MAX_VALUE (unlimited)")
        void monthlyRequestQuota() {
            assertThat(TenantPlan.ENTERPRISE.monthlyRequestQuota()).isEqualTo(Integer.MAX_VALUE);
        }

        @Test
        @DisplayName("maxRequestBodySize = 500 MB")
        void maxRequestBodySize() {
            assertThat(TenantPlan.ENTERPRISE.maxRequestBodySize()).isEqualTo(500L * 1024 * 1024);
        }
    }

    // ─── Cross-plan invariants ────────────────────────────────────────────────

    @Nested
    @DisplayName("Cross-plan invariants")
    class Invariants {

        @Test
        @DisplayName("Exactly 4 plan tiers exist")
        void fourTiers() {
            assertThat(TenantPlan.values()).hasSize(4);
        }

        @ParameterizedTest
        @EnumSource(TenantPlan.class)
        @DisplayName("All plans have positive maxRoutes")
        void positiveMaxRoutes(TenantPlan plan) {
            assertThat(plan.maxRoutes()).isPositive();
        }

        @ParameterizedTest
        @EnumSource(TenantPlan.class)
        @DisplayName("All plans have positive maxFilters")
        void positiveMaxFilters(TenantPlan plan) {
            assertThat(plan.maxFilters()).isPositive();
        }

        @ParameterizedTest
        @EnumSource(TenantPlan.class)
        @DisplayName("All plans have positive monthlyRequestQuota")
        void positiveMonthlyRequestQuota(TenantPlan plan) {
            assertThat(plan.monthlyRequestQuota()).isPositive();
        }

        @ParameterizedTest
        @EnumSource(TenantPlan.class)
        @DisplayName("All plans have positive maxRequestBodySize")
        void positiveMaxRequestBodySize(TenantPlan plan) {
            assertThat(plan.maxRequestBodySize()).isPositive();
        }

        @Test
        @DisplayName("Quotas increase monotonically from FREE to ENTERPRISE")
        void quotasIncreaseMonotonically() {
            TenantPlan[] plans = {TenantPlan.FREE, TenantPlan.STARTER, TenantPlan.PRO, TenantPlan.ENTERPRISE};

            for (int i = 1; i < plans.length; i++) {
                assertThat(plans[i].maxRoutes())
                        .as("%s.maxRoutes > %s.maxRoutes", plans[i], plans[i - 1])
                        .isGreaterThan(plans[i - 1].maxRoutes());

                assertThat(plans[i].maxFilters())
                        .as("%s.maxFilters > %s.maxFilters", plans[i], plans[i - 1])
                        .isGreaterThan(plans[i - 1].maxFilters());

                assertThat(plans[i].monthlyRequestQuota())
                        .as("%s.monthlyRequestQuota > %s.monthlyRequestQuota", plans[i], plans[i - 1])
                        .isGreaterThan(plans[i - 1].monthlyRequestQuota());

                assertThat(plans[i].maxRequestBodySize())
                        .as("%s.maxRequestBodySize > %s.maxRequestBodySize", plans[i], plans[i - 1])
                        .isGreaterThan(plans[i - 1].maxRequestBodySize());
            }
        }
    }
}

