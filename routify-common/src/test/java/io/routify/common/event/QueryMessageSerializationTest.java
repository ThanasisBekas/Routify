package io.routify.common.event;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.routify.common.domain.FilterType;
import io.routify.common.domain.RouteEnvironment;
import io.routify.common.domain.RouteStatus;
import io.routify.common.domain.TenantPlan;
import io.routify.common.domain.UserRole;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip serialisation tests for every {@link QueryRequest} and {@link QueryResponse}
 * sealed subtype.
 *
 * <p>These tests guard the RabbitMQ RPC contract — a broken {@code "type"} discriminator
 * causes the receiver to deserialise the message as {@code Unknown}, silently dropping
 * the request or response.
 */
class QueryMessageSerializationTest {

    private static ObjectMapper mapper;

    private static final UUID ID       = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT   = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ROUTE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID OTHER_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant NOW   = Instant.parse("2026-04-05T10:00:00Z");

    @BeforeAll
    static void setup() {
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    // ─── QueryRequest round-trips ─────────────────────────────────────────────

    static Stream<Arguments> allQueryRequests() {
        return Stream.of(
            // route-service
            Arguments.of(new QueryRequest.GatewaySnapshot(), "GATEWAY_SNAPSHOT"),
            Arguments.of(new QueryRequest.GatewayConfigGet(), "GATEWAY_CONFIG_GET"),
            Arguments.of(new QueryRequest.GatewayConfigSave("cors", "admin", Map.of("allowedOrigins", "*")), "GATEWAY_CONFIG_SAVE"),
            Arguments.of(new QueryRequest.RouteStats(TENANT), "ROUTE_STATS"),
            Arguments.of(new QueryRequest.RoutesQuery(TENANT, "ACTIVE", null, 0, 20, "name", "ASC"), "ROUTES_QUERY"),
            Arguments.of(new QueryRequest.RouteGet(ID, TENANT), "ROUTE_GET"),
            Arguments.of(new QueryRequest.RouteClone(ID, TENANT, "admin@routify.io"), "ROUTE_CLONE"),
            Arguments.of(new QueryRequest.FiltersQuery(TENANT, 0, 20, "name", "ASC"), "FILTERS_QUERY"),
            Arguments.of(new QueryRequest.FilterGet(ID, TENANT), "FILTER_GET"),

            // identity-service
            Arguments.of(new QueryRequest.AuthLogin("admin", "secret", "acme"), "AUTH_LOGIN"),
            Arguments.of(new QueryRequest.AuthRefresh("refresh-token-value"), "AUTH_REFRESH"),
            Arguments.of(new QueryRequest.AuthChangePassword(ID, "old-pass", "new-pass"), "AUTH_CHANGE_PASSWORD"),
            Arguments.of(new QueryRequest.AdminResetPassword(ID, TENANT, "new-pass"), "ADMIN_RESET_PASSWORD"),
            Arguments.of(new QueryRequest.UsersQuery(TENANT, 0, 20), "USERS_QUERY"),
            Arguments.of(new QueryRequest.UserGet(ID, TENANT), "USER_GET"),
            Arguments.of(new QueryRequest.TenantsQuery(0, 20), "TENANTS_QUERY"),
            Arguments.of(new QueryRequest.TenantGet(ID), "TENANT_GET"),
            Arguments.of(new QueryRequest.ListActiveWorkspaces(), "LIST_ACTIVE_WORKSPACES"),

            // audit-service
            Arguments.of(new QueryRequest.AuditEventsQuery(TENANT, "route.created", "ROUTE", ID.toString(), "2026-01-01", "2026-12-31", 0, 50), "AUDIT_EVENTS_QUERY"),
            Arguments.of(new QueryRequest.AuditRequestsQuery(TENANT, ROUTE_ID, "2026-01-01", "2026-12-31", 0, 50), "AUDIT_REQUESTS_QUERY"),
            Arguments.of(new QueryRequest.AuditRequestStats(TENANT, ROUTE_ID), "AUDIT_REQUEST_STATS"),
            Arguments.of(new QueryRequest.ReplayFailedQuery(TENANT, ROUTE_ID, 0, 20), "REPLAY_FAILED_QUERY"),
            Arguments.of(new QueryRequest.ReplayPendingQuery(TENANT, ROUTE_ID, 0, 20), "REPLAY_PENDING_QUERY"),
            Arguments.of(new QueryRequest.ReplayStats(TENANT), "REPLAY_STATS"),
            Arguments.of(new QueryRequest.ReplaySingle(ID, TENANT), "REPLAY_SINGLE"),
            Arguments.of(new QueryRequest.ReplayBulk(TENANT, 100), "REPLAY_BULK"),

            // cert-vault
            Arguments.of(new QueryRequest.CertsQuery(TENANT, "ACTIVE", 0, 20, "alias", "ASC"), "CERTS_QUERY"),
            Arguments.of(new QueryRequest.CertGet(ID, TENANT), "CERT_GET"),
            Arguments.of(new QueryRequest.CertsActiveList(TENANT), "CERTS_ACTIVE_LIST"),
            Arguments.of(new QueryRequest.CertStats(TENANT), "CERT_STATS"),
            Arguments.of(new QueryRequest.CertsGatewaySnapshot(TENANT), "CERTS_GATEWAY_SNAPSHOT"),
            Arguments.of(new QueryRequest.CertFetchMaterial(ID, TENANT), "CERT_FETCH_MATERIAL"),
            Arguments.of(new QueryRequest.CertGroupsQuery(TENANT, "ACTIVE", 0, 20, "alias", "ASC"), "CERT_GROUPS_QUERY"),
            Arguments.of(new QueryRequest.CertGroupGet(ID, TENANT), "CERT_GROUP_GET"),
            Arguments.of(new QueryRequest.CertGroupMembers(ID, TENANT), "CERT_GROUP_MEMBERS"),

            // ai-service
            Arguments.of(new QueryRequest.AiFilterEvaluate(
                ROUTE_ID.toString(), "users-api", TENANT.toString(),
                "Block SQL injection", "SYNC", true, 512, "ALLOW", 0.8, true, 300,
                "POST", "/api/users", "q=test", "192.168.1.1",
                Map.of("Content-Type", "application/json"), "eyJib2R5IjoiLi4uIn0=",
                ID.toString(), "TENANT_ADMIN", "corr-123"
            ), "AI_FILTER_EVALUATE"),
            Arguments.of(new QueryRequest.AiModifierEvaluate(
                ROUTE_ID.toString(), "users-api", TENANT.toString(),
                "Scrub PII from body", "BODY", null, 0.1, 1024, "PASSTHROUGH",
                true, 2048, true, 300, 5000,
                "POST", "/api/users", null, "192.168.1.1",
                Map.of("Content-Type", "application/json"), "eyJlbWFpbCI6InRlc3RAZXhhbXBsZS5jb20ifQ==",
                "corr-456"
            ), "AI_MODIFIER_EVALUATE"),

            // audit-service AI filter stats
            Arguments.of(new QueryRequest.AiFilterStatsQuery(TENANT, ROUTE_ID, "2026-01-01", "2026-12-31"), "AI_FILTER_STATS_QUERY"),
            Arguments.of(new QueryRequest.AiFilterDecisionsQuery(TENANT, ROUTE_ID, "BLOCK", "2026-01-01", "2026-12-31", 0, 50), "AI_FILTER_DECISIONS_QUERY")
        );
    }

    @ParameterizedTest(name = "QueryRequest: {1}")
    @MethodSource("allQueryRequests")
    @DisplayName("QueryRequest round-trip preserves type and fields")
    void queryRequestRoundTrip(QueryRequest request, String expectedType) throws Exception {
        String json = mapper.writeValueAsString(request);
        assertThat(json).contains("\"type\":\"" + expectedType + "\"");

        QueryRequest result = mapper.readValue(json, QueryRequest.class);
        assertThat(result).isNotInstanceOf(QueryRequest.Unknown.class);
        assertThat(result).isInstanceOf(request.getClass());
        assertThat(result).isEqualTo(request);
    }

    // ─── QueryResponse round-trips ────────────────────────────────────────────

    static Stream<Arguments> allQueryResponses() {
        return Stream.of(
            // route-service
            Arguments.of(new QueryResponse.GatewaySnapshotList(List.of(
                new QueryResponse.GatewaySnapshotList.RouteSnapshot(
                    ROUTE_ID, TENANT, "users-api", "/api/users/**", "GET,POST",
                    "http://user-service:8080", "1", 3, "PRODUCTION",
                    List.of(new QueryResponse.GatewaySnapshotList.RouteSnapshot.FilterSnapshot(
                        OTHER_ID, "RATE_LIMIT_FIXED_WINDOW", 1, "PRE", Map.of("limit", 10), Map.of()
                    )), Map.of("timeout", (Object) 5000), 100, null)
            )), "GATEWAY_SNAPSHOT_LIST"),

            Arguments.of(new QueryResponse.GatewayConfig(Map.of("cors", Map.of("allowedOrigins", "*"))), "GATEWAY_CONFIG"),

            Arguments.of(new QueryResponse.RouteStatsResult(100, 60, 30, 10, TENANT), "ROUTE_STATS_RESULT"),

            Arguments.of(new QueryResponse.RoutesPage(
                List.of(new QueryResponse.RoutesPage.RouteSummary(
                    ID, "users-api", "User routes", "/api/users/**", "GET,POST",
                    "http://user:8080", RouteStatus.ACTIVE, RouteEnvironment.PRODUCTION, 2, 3, NOW, NOW,
                    100, null
                )), 1, 1, 0, 20
            ), "ROUTES_PAGE"),

            Arguments.of(new QueryResponse.RouteDetail(
                ID, TENANT, "users-api", "User routes", "/api/users/**", "GET,POST",
                "http://user:8080", "1", RouteStatus.ACTIVE, RouteEnvironment.PRODUCTION, 2,
                List.of(new QueryResponse.RouteDetail.FilterRef(OTHER_ID, "rate-limiter", "RATE_LIMIT_FIXED_WINDOW", 1, "PRE", true)),
                Map.of("timeout", (Object) 5000), "admin", NOW, NOW, NOW,
                100, null, null
            ), "ROUTE_DETAIL"),

            Arguments.of(new QueryResponse.FiltersPage(
                List.of(new QueryResponse.FiltersPage.FilterSummary(
                    ID, "rate-limiter", FilterType.RATE_LIMIT_FIXED_WINDOW, true, 5,
                    new QueryResponse.GatewayConfigRef("RATE_LIMIT", "rl-1", "Default RL"), NOW
                )), 1, 1, 0, 20
            ), "FILTERS_PAGE"),

            Arguments.of(new QueryResponse.FilterDetail(
                ID, TENANT, "rate-limiter", "10 req/s", FilterType.RATE_LIMIT_FIXED_WINDOW,
                Map.of("limit", 10), false, true, 5,
                new QueryResponse.GatewayConfigRef("RATE_LIMIT", "rl-1", "Default RL"),
                "admin", NOW, NOW
            ), "FILTER_DETAIL"),

            // identity-service
            Arguments.of(new QueryResponse.LoginResult(
                "eyJhbGciOiJSUzI1NiJ9...", "refresh-token", "Bearer", 3600, false,
                new QueryResponse.LoginResult.UserInfo(ID, TENANT, "admin", "admin@acme.com", UserRole.SUPER_ADMIN, false, List.of())
            ), "LOGIN_RESULT"),

            Arguments.of(new QueryResponse.PasswordChangeResult(true), "PASSWORD_CHANGE_RESULT"),

            Arguments.of(new QueryResponse.UsersPage(
                List.of(new QueryResponse.UsersPage.UserSummary(
                    ID, TENANT, "jane.doe", "jane@acme.com", UserRole.TENANT_ADMIN,
                    "ACTIVE", false, NOW, NOW, OTHER_ID, "Tenant Admin", List.of("ROUTES_READ", "ROUTES_WRITE")
                )), 1, 1, 0, 20
            ), "USERS_PAGE"),

            Arguments.of(new QueryResponse.UserDetail(
                ID, TENANT, "jane.doe", "jane@acme.com", UserRole.TENANT_ADMIN,
                "ACTIVE", false, NOW, NOW, OTHER_ID, "Tenant Admin", List.of("ROUTES_READ", "ROUTES_WRITE")
            ), "USER_DETAIL"),

            Arguments.of(new QueryResponse.TenantsPage(
                List.of(new QueryResponse.TenantsPage.TenantSummary(
                    TENANT, "Acme Corp", "acme", "ACTIVE", TenantPlan.PRO, "admin@acme.com", NOW
                )), 1, 1, 0, 20
            ), "TENANTS_PAGE"),

            Arguments.of(new QueryResponse.TenantDetail(
                TENANT, "Acme Corp", "acme", "ACTIVE", TenantPlan.PRO, "admin@acme.com", NOW
            ), "TENANT_DETAIL"),

            Arguments.of(new QueryResponse.ActiveWorkspacesList(
                List.of(new QueryResponse.ActiveWorkspacesList.WorkspaceInfo("Acme Corp", "acme"))
            ), "ACTIVE_WORKSPACES_LIST"),

            // audit-service
            Arguments.of(new QueryResponse.AuditEventsPage(
                List.of(new QueryResponse.AuditEventsPage.AuditEventEntry(
                    ID, TENANT, "route.created", "ROUTE", ROUTE_ID.toString(),
                    "admin", "corr-123", NOW, NOW
                )), 1, 1, 0, 50
            ), "AUDIT_EVENTS_PAGE"),

            Arguments.of(new QueryResponse.RequestLogsPage(
                List.of(new QueryResponse.RequestLogsPage.RequestLogEntry(
                    ID, TENANT, ROUTE_ID, "users-api", "corr-1", "GET", "/api/users", null,
                    "http://user:8080", 200, 45L, "192.168.1.1", ID.toString(), null,
                    false, "{}", "{}", null, null, null, 0, null, null, null, NOW
                )), 1, 1, 0, 50
            ), "REQUEST_LOGS_PAGE"),

            Arguments.of(new QueryResponse.RequestStatsResult(ROUTE_ID, 5000, 42.5, 350, 12), "REQUEST_STATS_RESULT"),

            Arguments.of(new QueryResponse.ReplayStatsResult(10, 2, 85, 3, 0), "REPLAY_STATS_RESULT"),

            Arguments.of(new QueryResponse.ReplaySingleResult(ID, "SUCCEEDED", 200, "Replay completed"), "REPLAY_SINGLE_RESULT"),

            Arguments.of(new QueryResponse.ReplayBulkResult(20, 18, 1, 1), "REPLAY_BULK_RESULT"),

            // cert-vault
            Arguments.of(new QueryResponse.CertsPage(List.of(), 0, 0, 0, 20, true, true), "CERTS_PAGE"),

            Arguments.of(new QueryResponse.CertDetail(
                ID, TENANT, "web-tls-1", "Production", "Main TLS cert", "PEM", "ACTIVE", "VALID",
                "CN=acme.com", "CN=Let's Encrypt", "ABC123", NOW, NOW.plusSeconds(86400 * 365),
                "SHA256withRSA", "RSA", 2048, "aa:bb:cc", "dd:ee:ff",
                List.of("acme.com", "*.acme.com"), List.of(), false, true,
                "default-tls", OTHER_ID, "mtls-group", "primary", "default-tls",
                "admin", NOW, NOW, NOW.plusSeconds(86400 * 365)
            ), "CERT_DETAIL"),

            Arguments.of(new QueryResponse.CertsList(List.of()), "CERTS_LIST"),

            Arguments.of(new QueryResponse.CertStatsResult(50, 40, 5, Map.of("ACTIVE", 40L, "EXPIRED", 10L)), "CERT_STATS_RESULT"),

            Arguments.of(new QueryResponse.CertGroupsPage(List.of(), 0, 0, 0, 20, true, true), "CERT_GROUPS_PAGE"),

            Arguments.of(new QueryResponse.CertGroupDetail(
                ID, TENANT, "mtls-clients", "mTLS Clients", "Client certificates", "ACTIVE",
                3, "HEALTHY", List.of(), "admin", NOW, NOW
            ), "CERT_GROUP_DETAIL"),

            Arguments.of(new QueryResponse.CertGroupMembersList(List.of()), "CERT_GROUP_MEMBERS_LIST"),

            // api-gateway
            Arguments.of(new QueryResponse.GatewayStatus(
                "UP", 42, "2026-04-05T08:00:00Z", Map.of("cors", true), "2026-04-05T10:00:00Z"
            ), "GATEWAY_STATUS"),

            Arguments.of(new QueryResponse.CertRegistrySnapshot(Map.of(
                "default-tls", new QueryResponse.CertRegistrySnapshot.CertRegistryEntry(
                    "aa:bb:cc:dd", "2027-04-05T10:00:00Z", "CERT_VAULT", "ACTIVE", 1)
            )), "CERT_REGISTRY_SNAPSHOT"),

            // ai-service
            Arguments.of(new QueryResponse.AiFilterVerdict(
                "BLOCK", "SQL injection detected", 0.95, false, false, 320, "eval-123"
            ), "AI_FILTER_VERDICT"),

            Arguments.of(new QueryResponse.AiModifierVerdict(
                "mod-456", true, "PII_SCRUB", Map.of("X-Modified", "true"),
                "{\"email\":\"[REDACTED]\"}", "Scrubbed 1 email address", false, 450
            ), "AI_MODIFIER_VERDICT"),

            // audit-service AI filter stats
            Arguments.of(new QueryResponse.AiFilterStatsResult(
                TENANT, ROUTE_ID, 1000, 800, 150, 30, 20, 600,
                45.2, 120, 350, "2026-01-01T00:00:00Z", "2026-12-31T23:59:59Z"
            ), "AI_FILTER_STATS_RESULT"),

            Arguments.of(new QueryResponse.AiFilterDecisionsPage(
                List.of(new QueryResponse.AiFilterDecisionsPage.AiFilterDecisionEntry(
                    "eval-789", ROUTE_ID, "users-api", TENANT, "BLOCK",
                    "SQL injection", 0.95, false, "SYNC", 320,
                    "POST", "/api/users", "192.168.1.1", NOW
                )), 1, 1, 0, 50
            ), "AI_FILTER_DECISIONS_PAGE")
        );
    }

    @ParameterizedTest(name = "QueryResponse: {1}")
    @MethodSource("allQueryResponses")
    @DisplayName("QueryResponse round-trip preserves type and fields")
    void queryResponseRoundTrip(QueryResponse response, String expectedType) throws Exception {
        String json = mapper.writeValueAsString(response);
        assertThat(json).contains("\"type\":\"" + expectedType + "\"");

        QueryResponse result = mapper.readValue(json, QueryResponse.class);
        assertThat(result).isNotInstanceOf(QueryResponse.Unknown.class);
        assertThat(result).isInstanceOf(response.getClass());
        assertThat(result).isEqualTo(response);
    }

    // ─── Edge cases ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Unknown QueryRequest type falls back to Unknown")
        void unknownRequestType() throws Exception {
            String json = """
                {"type":"NONEXISTENT_QUERY","tenantId":"22222222-2222-2222-2222-222222222222"}
                """;
            QueryRequest result = mapper.readValue(json, QueryRequest.class);
            assertThat(result).isInstanceOf(QueryRequest.Unknown.class);
        }

        @Test
        @DisplayName("Unknown QueryResponse type falls back to Unknown")
        void unknownResponseType() throws Exception {
            String json = """
                {"type":"NONEXISTENT_RESPONSE","data":"test"}
                """;
            QueryResponse result = mapper.readValue(json, QueryResponse.class);
            assertThat(result).isInstanceOf(QueryResponse.Unknown.class);
        }

        @Test
        @DisplayName("Missing type in QueryRequest falls back to Unknown")
        void missingRequestType() throws Exception {
            String json = """
                {"tenantId":"22222222-2222-2222-2222-222222222222"}
                """;
            QueryRequest result = mapper.readValue(json, QueryRequest.class);
            assertThat(result).isInstanceOf(QueryRequest.Unknown.class);
        }

        @Test
        @DisplayName("Missing type in QueryResponse falls back to Unknown")
        void missingResponseType() throws Exception {
            String json = """
                {"total":100}
                """;
            QueryResponse result = mapper.readValue(json, QueryResponse.class);
            assertThat(result).isInstanceOf(QueryResponse.Unknown.class);
        }

        @Test
        @DisplayName("All QueryRequest permits are covered by @JsonSubTypes")
        void allRequestPermitsCovered() {
            var subtypes = QueryRequest.class.getAnnotation(com.fasterxml.jackson.annotation.JsonSubTypes.class);
            assertThat(subtypes).isNotNull();
            var permits = QueryRequest.class.getPermittedSubclasses();
            // -1 for Unknown (handled via defaultImpl)
            assertThat(subtypes.value()).hasSize(permits.length - 1);
        }

        @Test
        @DisplayName("All QueryResponse permits are covered by @JsonSubTypes")
        void allResponsePermitsCovered() {
            var subtypes = QueryResponse.class.getAnnotation(com.fasterxml.jackson.annotation.JsonSubTypes.class);
            assertThat(subtypes).isNotNull();
            var permits = QueryResponse.class.getPermittedSubclasses();
            // -1 for Unknown (handled via defaultImpl)
            assertThat(subtypes.value()).hasSize(permits.length - 1);
        }

        @Test
        @DisplayName("AiFilterVerdict.fallback() factory round-trips")
        void aiFilterVerdictFallbackRoundTrips() throws Exception {
            var verdict = QueryResponse.AiFilterVerdict.fallback("ALLOW", "Circuit open", 5);
            String json = mapper.writeValueAsString(verdict);
            QueryResponse result = mapper.readValue(json, QueryResponse.class);
            assertThat(result).isEqualTo(verdict);
        }

        @Test
        @DisplayName("AiModifierVerdict.passthrough() factory round-trips")
        void aiModifierVerdictPassthroughRoundTrips() throws Exception {
            var verdict = QueryResponse.AiModifierVerdict.passthrough("LLM unavailable", 10);
            String json = mapper.writeValueAsString(verdict);
            QueryResponse result = mapper.readValue(json, QueryResponse.class);
            assertThat(result).isEqualTo(verdict);
        }

        @Test
        @DisplayName("Empty list fields round-trip correctly")
        void emptyListsRoundTrip() throws Exception {
            var snapshot = new QueryResponse.GatewaySnapshotList(List.of());
            String json = mapper.writeValueAsString(snapshot);
            QueryResponse result = mapper.readValue(json, QueryResponse.class);
            assertThat(result).isEqualTo(snapshot);
        }

        @Test
        @DisplayName("Null optional fields in QueryRequest round-trip")
        void nullOptionalFieldsInRequest() throws Exception {
            var req = new QueryRequest.AuditEventsQuery(TENANT, null, null, null, null, null, 0, 20);
            String json = mapper.writeValueAsString(req);
            QueryRequest result = mapper.readValue(json, QueryRequest.class);
            assertThat(result).isEqualTo(req);
        }
    }
}

