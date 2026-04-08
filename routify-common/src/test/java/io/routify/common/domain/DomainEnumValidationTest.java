package io.routify.common.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for domain enum constants: {@link UserRole}, {@link RouteStatus},
 * {@link RouteEnvironment}, {@link Permission}, {@link AlertMetric}, and
 * {@link WebhookEventType}.
 *
 * <p>Guards against accidental value removals or renames that would break
 * cross-service contracts (Kafka events, JWT claims, dashboard TypeScript unions).
 */
class DomainEnumValidationTest {

    // ─── UserRole ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("UserRole enum")
    class UserRoleTests {

        @Test
        @DisplayName("Exactly 4 roles exist")
        void exactlyFourRoles() {
            assertThat(UserRole.values()).hasSize(4);
        }

        @Test
        @DisplayName("All documented roles exist")
        void documentedRolesExist() {
            assertThat(UserRole.values()).containsExactlyInAnyOrder(
                    UserRole.SUPER_ADMIN, UserRole.TENANT_ADMIN, UserRole.VIEWER, UserRole.OPERATOR
            );
        }

        @ParameterizedTest
        @EnumSource(UserRole.class)
        @DisplayName("valueOf round-trip for each role")
        void valueOfRoundTrip(UserRole role) {
            assertThat(UserRole.valueOf(role.name())).isEqualTo(role);
        }
    }

    // ─── RouteStatus ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("RouteStatus enum")
    class RouteStatusTests {

        @Test
        @DisplayName("Exactly 4 statuses exist")
        void exactlyFourStatuses() {
            assertThat(RouteStatus.values()).hasSize(4);
        }

        @Test
        @DisplayName("Lifecycle statuses: DRAFT, ACTIVE, DISABLED, ARCHIVED")
        void lifecycleStatuses() {
            assertThat(RouteStatus.values()).containsExactlyInAnyOrder(
                    RouteStatus.DRAFT, RouteStatus.ACTIVE, RouteStatus.DISABLED, RouteStatus.ARCHIVED
            );
        }

        @Test
        @DisplayName("Ordinal order matches lifecycle: DRAFT → ACTIVE → DISABLED → ARCHIVED")
        void ordinalMatchesLifecycle() {
            assertThat(RouteStatus.DRAFT.ordinal()).isLessThan(RouteStatus.ACTIVE.ordinal());
            assertThat(RouteStatus.ACTIVE.ordinal()).isLessThan(RouteStatus.DISABLED.ordinal());
            assertThat(RouteStatus.DISABLED.ordinal()).isLessThan(RouteStatus.ARCHIVED.ordinal());
        }
    }

    // ─── RouteEnvironment ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("RouteEnvironment enum")
    class RouteEnvironmentTests {

        @Test
        @DisplayName("Exactly 2 environments exist")
        void exactlyTwoEnvironments() {
            assertThat(RouteEnvironment.values()).hasSize(2);
        }

        @Test
        @DisplayName("STAGING and PRODUCTION exist")
        void environmentsExist() {
            assertThat(RouteEnvironment.values()).containsExactlyInAnyOrder(
                    RouteEnvironment.STAGING, RouteEnvironment.PRODUCTION
            );
        }
    }

    // ─── Permission ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Permission enum")
    class PermissionTests {

        @Test
        @DisplayName("Exactly 27 permissions exist")
        void exactly27Permissions() {
            assertThat(Permission.values()).hasSize(27);
        }

        @Test
        @DisplayName("code() returns name()")
        void codeReturnsName() {
            for (Permission perm : Permission.values()) {
                assertThat(perm.code()).isEqualTo(perm.name());
            }
        }

        @Test
        @DisplayName("Route permissions exist")
        void routePermissions() {
            assertThat(Permission.valueOf("ROUTES_READ")).isNotNull();
            assertThat(Permission.valueOf("ROUTES_WRITE")).isNotNull();
            assertThat(Permission.valueOf("ROUTES_ACTIVATE")).isNotNull();
            assertThat(Permission.valueOf("ROUTES_DELETE")).isNotNull();
            assertThat(Permission.valueOf("ROUTES_PROMOTE")).isNotNull();
        }

        @Test
        @DisplayName("Filter permissions exist")
        void filterPermissions() {
            assertThat(Permission.valueOf("FILTERS_READ")).isNotNull();
            assertThat(Permission.valueOf("FILTERS_WRITE")).isNotNull();
            assertThat(Permission.valueOf("FILTERS_DELETE")).isNotNull();
        }

        @Test
        @DisplayName("User permissions exist")
        void userPermissions() {
            assertThat(Permission.valueOf("USERS_READ")).isNotNull();
            assertThat(Permission.valueOf("USERS_WRITE")).isNotNull();
            assertThat(Permission.valueOf("USERS_DELETE")).isNotNull();
        }

        @Test
        @DisplayName("Certificate permissions exist")
        void certPermissions() {
            assertThat(Permission.valueOf("CERTS_READ")).isNotNull();
            assertThat(Permission.valueOf("CERTS_WRITE")).isNotNull();
            assertThat(Permission.valueOf("CERTS_ADMIN")).isNotNull();
        }

        @Test
        @DisplayName("Tenant permissions exist (SUPER_ADMIN only)")
        void tenantPermissions() {
            assertThat(Permission.valueOf("TENANTS_READ")).isNotNull();
            assertThat(Permission.valueOf("TENANTS_WRITE")).isNotNull();
            assertThat(Permission.valueOf("TENANTS_SUSPEND")).isNotNull();
        }

        @ParameterizedTest
        @EnumSource(Permission.class)
        @DisplayName("All permission names are UPPER_SNAKE_CASE")
        void allNamesUpperSnakeCase(Permission perm) {
            assertThat(perm.name()).matches("[A-Z][A-Z0-9_]*");
        }
    }

    // ─── AlertMetric ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("AlertMetric enum")
    class AlertMetricTests {

        @Test
        @DisplayName("Exactly 8 alert metric types exist")
        void exactlyEightMetrics() {
            assertThat(AlertMetric.values()).hasSize(8);
        }

        @Test
        @DisplayName("All documented metric types exist")
        void documentedMetricsExist() {
            assertThat(AlertMetric.values()).containsExactlyInAnyOrder(
                    AlertMetric.ERROR_RATE, AlertMetric.P99_LATENCY,
                    AlertMetric.DLQ_DEPTH, AlertMetric.CERT_EXPIRY_DAYS,
                    AlertMetric.QUOTA_USAGE, AlertMetric.SLO_BUDGET,
                    AlertMetric.REQUEST_VOLUME, AlertMetric.AUTH_FAILURE_RATE
            );
        }

        @ParameterizedTest
        @EnumSource(AlertMetric.class)
        @DisplayName("valueOf round-trip for each metric")
        void valueOfRoundTrip(AlertMetric metric) {
            assertThat(AlertMetric.valueOf(metric.name())).isEqualTo(metric);
        }
    }

    // ─── WebhookEventType ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("WebhookEventType enum")
    class WebhookEventTypeTests {

        @Test
        @DisplayName("All route event types exist")
        void routeEventTypes() {
            assertThat(WebhookEventType.valueOf("ROUTE_CREATED")).isNotNull();
            assertThat(WebhookEventType.valueOf("ROUTE_ACTIVATED")).isNotNull();
            assertThat(WebhookEventType.valueOf("ROUTE_DEACTIVATED")).isNotNull();
            assertThat(WebhookEventType.valueOf("ROUTE_DELETED")).isNotNull();
            assertThat(WebhookEventType.valueOf("ROUTE_PROMOTED")).isNotNull();
        }

        @Test
        @DisplayName("All cert event types exist")
        void certEventTypes() {
            assertThat(WebhookEventType.valueOf("CERT_UPLOADED")).isNotNull();
            assertThat(WebhookEventType.valueOf("CERT_REVOKED")).isNotNull();
            assertThat(WebhookEventType.valueOf("CERT_EXPIRING")).isNotNull();
            assertThat(WebhookEventType.valueOf("CERT_EXPIRED")).isNotNull();
        }

        @Test
        @DisplayName("All quota event types exist")
        void quotaEventTypes() {
            assertThat(WebhookEventType.valueOf("QUOTA_WARNING")).isNotNull();
            assertThat(WebhookEventType.valueOf("QUOTA_EXCEEDED")).isNotNull();
        }

        @Test
        @DisplayName("All canary event types exist")
        void canaryEventTypes() {
            assertThat(WebhookEventType.valueOf("CANARY_DEPLOYED")).isNotNull();
            assertThat(WebhookEventType.valueOf("CANARY_PROMOTED")).isNotNull();
            assertThat(WebhookEventType.valueOf("CANARY_ROLLBACK")).isNotNull();
        }

        @Test
        @DisplayName("All alerting event types exist")
        void alertingEventTypes() {
            assertThat(WebhookEventType.valueOf("ALERT_FIRED")).isNotNull();
            assertThat(WebhookEventType.valueOf("ALERT_RESOLVED")).isNotNull();
        }

        @Test
        @DisplayName("Infrastructure event types exist")
        void infrastructureEventTypes() {
            assertThat(WebhookEventType.valueOf("DLQ_OVERFLOW")).isNotNull();
            assertThat(WebhookEventType.valueOf("GATEWAY_RELOAD_FAILED")).isNotNull();
            assertThat(WebhookEventType.valueOf("GATEWAY_CONFIG_DRIFT")).isNotNull();
        }

        @Test
        @DisplayName("Total webhook event types count")
        void totalCount() {
            // 5 route + 3 filter + 4 cert + 4 user/tenant + 2 AI + 3 infra + 2 quota + 3 canary + 2 alert = 28
            assertThat(WebhookEventType.values().length).isEqualTo(28);
        }

        @ParameterizedTest
        @EnumSource(WebhookEventType.class)
        @DisplayName("All names are UPPER_SNAKE_CASE")
        void allNamesUpperSnakeCase(WebhookEventType type) {
            assertThat(type.name()).matches("[A-Z][A-Z0-9_]*");
        }
    }
}

