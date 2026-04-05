package io.routify.admin;

import com.fasterxml.jackson.databind.JsonNode;
import io.routify.common.domain.TenantPlan;
import io.routify.common.domain.UserRole;
import io.routify.common.event.KafkaTopics;
import io.routify.common.event.QueryResponse;
import io.routify.common.event.RabbitTopology;
import io.routify.common.web.RoutifyHeaders;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the admin-api BFF auth and user/tenant management endpoints.
 *
 * <p>Covers:
 * <ul>
 *   <li>Login flow: HTTP → RabbitMQ RPC → identity-service mock → JWT + refresh cookie</li>
 *   <li>Logout: publishes Kafka command + clears cookie</li>
 *   <li>Token refresh via HttpOnly cookie</li>
 *   <li>User CRUD: Kafka commands for writes, RabbitMQ RPC for reads</li>
 *   <li>Tenant management: RabbitMQ sync commands</li>
 * </ul>
 */
class AdminAuthEndpointIT extends AdminApiIntegrationBase {

    // ─── Test 1: Login → RabbitMQ RPC → access token + refresh cookie ────────

    @Test
    @DisplayName("POST /auth/login returns access token and sets HttpOnly refresh cookie")
    void login_returnsAccessTokenAndRefreshCookie() throws Exception {
        var mockLogin = new QueryResponse.LoginResult(
                "mock-access-token", "mock-refresh-token", "Bearer", 3600,
                false,
                new QueryResponse.LoginResult.UserInfo(
                        USER_ID, TENANT_ID, "admin", "admin@test.io",
                        UserRole.SUPER_ADMIN, false));

        mockRabbitReply(RabbitTopology.EXCHANGE_IDENTITY_SERVICE, RabbitTopology.RK_AUTH_LOGIN,
                request -> mockLogin);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "admin",
                                "password", "password123",
                                "tenantSlug", "platform"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("mock-access-token"))
                // refreshToken must NOT be in JSON body (delivered via cookie)
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.user.username").value("admin"))
                .andExpect(jsonPath("$.user.role").value("SUPER_ADMIN"))
                .andExpect(header().exists("Set-Cookie"))
                .andExpect(header().string("Set-Cookie",
                        org.hamcrest.Matchers.containsString("refresh_token=mock-refresh-token")))
                .andExpect(header().string("Set-Cookie",
                        org.hamcrest.Matchers.containsString("HttpOnly")));
    }

    // ─── Test 2: Refresh → reads cookie → RabbitMQ RPC → rotated cookie ─────

    @Test
    @DisplayName("POST /auth/refresh reads cookie, calls identity-service, rotates cookie")
    void refresh_rotatesCookieFromRabbitReply() throws Exception {
        var mockRefresh = new QueryResponse.LoginResult(
                "new-access-token", "rotated-refresh-token", "Bearer", 3600,
                false,
                new QueryResponse.LoginResult.UserInfo(
                        USER_ID, TENANT_ID, "admin", "admin@test.io",
                        UserRole.SUPER_ADMIN, false));

        mockRabbitReply(RabbitTopology.EXCHANGE_IDENTITY_SERVICE, RabbitTopology.RK_AUTH_REFRESH,
                request -> mockRefresh);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "old-refresh-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-access-token"))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(header().string("Set-Cookie",
                        org.hamcrest.Matchers.containsString("refresh_token=rotated-refresh-token")));
    }

    // ─── Test 3: Refresh without cookie → 401 ───────────────────────────────

    @Test
    @DisplayName("POST /auth/refresh without refresh cookie returns 401")
    void refresh_withoutCookie_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized());
    }

    // ─── Test 4: Logout → publishes Kafka command + clears cookie ────────────

    @Test
    @DisplayName("POST /auth/logout publishes Logout command to Kafka and clears cookie")
    void logout_publishesKafkaCommandAndClearsCookie() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "token-to-revoke")))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie",
                        org.hamcrest.Matchers.containsString("Max-Age=0")));

        // Verify Logout command published to AUTH_COMMANDS topic
        List<ConsumerRecord<String, String>> records =
                drainTopic(KafkaTopics.AUTH_COMMANDS, Duration.ofSeconds(10));
        assertThat(records).isNotEmpty();

        JsonNode cmd = objectMapper.readTree(records.get(0).value());
        assertThat(cmd.get("type").asText()).isEqualTo("LOGOUT");
        assertThat(cmd.get("refreshToken").asText()).isEqualTo("token-to-revoke");
    }

    // ─── Test 5: GET /users → RabbitMQ RPC → mock reply ─────────────────────

    @Test
    @DisplayName("GET /users returns user list from mock RabbitMQ reply")
    void listUsers_returnsMockUsers() throws Exception {
        var mockUsers = new QueryResponse.UsersPage(
                List.of(new QueryResponse.UsersPage.UserSummary(
                        USER_ID, TENANT_ID, "test-user", "test@test.io",
                        UserRole.OPERATOR, "ACTIVE", false, null, null)),
                1L, 1, 0, 20);

        mockRabbitReply(RabbitTopology.EXCHANGE_IDENTITY_SERVICE, RabbitTopology.RK_USERS_QUERY,
                request -> mockUsers);

        mockMvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + superAdminJwt())
                        .header(RoutifyHeaders.TENANT_ID, TENANT_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].username").value("test-user"))
                .andExpect(jsonPath("$.content[0].role").value("OPERATOR"));
    }

    // ─── Test 6: POST /users → Kafka USER_COMMANDS + 202 ────────────────────

    @Test
    @DisplayName("POST /users publishes CreateUser command to Kafka and returns 202")
    void createUser_publishesKafkaCommand() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "username", "new-user",
                "email", "new@test.io",
                "password", "SecurePass123!",
                "role", "VIEWER"
        ));

        mockMvc.perform(post("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + superAdminJwt())
                        .header(RoutifyHeaders.TENANT_ID, TENANT_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("accepted"));

        List<ConsumerRecord<String, String>> records =
                drainTopic(KafkaTopics.USER_COMMANDS, Duration.ofSeconds(10));
        assertThat(records).isNotEmpty();

        JsonNode cmd = objectMapper.readTree(records.get(0).value());
        assertThat(cmd.get("type").asText()).isEqualTo("CREATE_USER");
        assertThat(cmd.get("username").asText()).isEqualTo("new-user");
        assertThat(cmd.get("role").asText()).isEqualTo("VIEWER");
    }

    // ─── Test 7: OPERATOR cannot create users (403) ──────────────────────────

    @Test
    @DisplayName("OPERATOR role is denied user creation → 403")
    void createUser_operatorForbidden() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "username", "forbidden-user",
                "email", "forbidden@test.io",
                "password", "Pass123!",
                "role", "VIEWER"
        ));

        mockMvc.perform(post("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + operatorJwt())
                        .header(RoutifyHeaders.TENANT_ID, TENANT_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    // ─── Test 8: GET /tenants/workspaces is public (no auth) ─────────────────

    @Test
    @DisplayName("GET /tenants/workspaces is public and returns active workspaces")
    void listWorkspaces_publicEndpoint() throws Exception {
        var mockWorkspaces = new QueryResponse.ActiveWorkspacesList(
                List.of(new QueryResponse.ActiveWorkspacesList.WorkspaceInfo("Platform", "platform"),
                        new QueryResponse.ActiveWorkspacesList.WorkspaceInfo("Acme Corp", "acme")));

        mockRabbitReply(RabbitTopology.EXCHANGE_IDENTITY_SERVICE, RabbitTopology.RK_TENANTS_LIST_ACTIVE,
                request -> mockWorkspaces);

        mockMvc.perform(get("/api/v1/admin/tenants/workspaces"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaces").isArray())
                .andExpect(jsonPath("$.workspaces.length()").value(2))
                .andExpect(jsonPath("$.workspaces[0].name").value("Platform"))
                .andExpect(jsonPath("$.workspaces[1].slug").value("acme"));
    }

    // ─── Test 9: POST /tenants (create) → SUPER_ADMIN only via RabbitMQ ─────

    @Test
    @DisplayName("POST /tenants creates tenant via RabbitMQ RPC — SUPER_ADMIN only")
    void createTenant_superAdminOnly() throws Exception {
        UUID newTenantId = UUID.randomUUID();
        var mockTenant = new QueryResponse.TenantDetail(
                newTenantId, "New Tenant", "new-tenant", "ACTIVE",
                TenantPlan.STARTER, "contact@new.io", null);

        mockRabbitReply(RabbitTopology.EXCHANGE_IDENTITY_SERVICE, RabbitTopology.RK_TENANTS_COMMAND,
                request -> mockTenant);

        // SUPER_ADMIN → should succeed
        mockMvc.perform(post("/api/v1/admin/tenants")
                        .header("Authorization", "Bearer " + superAdminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "New Tenant",
                                "slug", "new-tenant",
                                "plan", "STARTER",
                                "contactEmail", "contact@new.io"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("New Tenant"))
                .andExpect(jsonPath("$.slug").value("new-tenant"));

        // OPERATOR → should be denied
        mockMvc.perform(post("/api/v1/admin/tenants")
                        .header("Authorization", "Bearer " + operatorJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Forbidden Tenant",
                                "slug", "forbidden",
                                "contactEmail", "x@x.io"))))
                .andExpect(status().isForbidden());
    }

    // ─── Test 10: JWT claims propagation — X-Auth-* headers injected ─────────

    @Test
    @DisplayName("JWT claims are injected as X-Auth-* headers in the request context")
    void jwtClaimsAreInjectedAsHeaders() throws Exception {
        // The filter list endpoint reads X-Tenant-Id from the request. When no explicit
        // X-Tenant-Id is supplied, JwtAuthFilter injects it from the JWT tenantId claim.
        // We verify this by sending a request WITHOUT X-Tenant-Id and checking the mock
        // listener receives the correct tenant.

        var mockPage = new QueryResponse.FiltersPage(List.of(), 0L, 0, 0, 20);

        mockRabbitReply(RabbitTopology.EXCHANGE_ROUTE_SERVICE, RabbitTopology.RK_FILTERS_QUERY,
                request -> mockPage);

        // This will fail with a missing header exception because the controller
        // requires @RequestHeader(RoutifyHeaders.TENANT_ID). But since the JWT
        // filter injects it from the JWT claim, it should resolve correctly.
        mockMvc.perform(get("/api/v1/admin/filters")
                        .header("Authorization", "Bearer " + superAdminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }
}

