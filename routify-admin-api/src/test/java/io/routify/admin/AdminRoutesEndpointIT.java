package io.routify.admin;

import com.fasterxml.jackson.databind.JsonNode;
import io.routify.common.domain.FilterType;
import io.routify.common.domain.RouteStatus;
import io.routify.common.event.KafkaTopics;
import io.routify.common.event.QueryResponse;
import io.routify.common.event.RabbitTopology;
import io.routify.common.web.RoutifyHeaders;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the admin-api BFF route and filter endpoints.
 *
 * <p>Tests the full flow:
 * <ul>
 *   <li><b>Write (POST/PUT/DELETE):</b> HTTP request → Kafka command published → HTTP 202</li>
 *   <li><b>Read (GET):</b> HTTP request → RabbitMQ RPC → mock service reply → HTTP 200</li>
 *   <li><b>Authorization:</b> role-based access control via JWT claims</li>
 * </ul>
 *
 * <p>Uses real Kafka + RabbitMQ containers; downstream services are mocked via
 * RabbitMQ reply listeners registered in {@link AdminApiIntegrationBase}.
 */
class AdminRoutesEndpointIT extends AdminApiIntegrationBase {

    // ─── Test 1: CreateRoute → Kafka command published + HTTP 202 ─────────────

    @Test
    @DisplayName("POST /routes publishes CreateRoute command to Kafka and returns 202")
    void createRoute_publishesKafkaCommandAndReturns202() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "name", "test-route",
                "description", "A test route",
                "pathPattern", "/api/v1/test/**",
                "methods", "GET,POST",
                "upstreamUri", "http://upstream:8080"
        ));

        mockMvc.perform(post("/api/v1/admin/routes")
                        .header("Authorization", "Bearer " + operatorJwt())
                        .header(RoutifyHeaders.TENANT_ID, TENANT_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.message").value("Route creation in progress"));

        // Verify the command appeared on the Kafka ROUTE_COMMANDS topic
        List<ConsumerRecord<String, String>> records =
                drainTopic(KafkaTopics.ROUTE_COMMANDS, Duration.ofSeconds(10));
        assertThat(records).isNotEmpty();

        JsonNode cmd = objectMapper.readTree(records.get(0).value());
        assertThat(cmd.get("type").asText()).isEqualTo("CREATE_ROUTE");
        assertThat(cmd.get("name").asText()).isEqualTo("test-route");
        assertThat(cmd.get("pathPattern").asText()).isEqualTo("/api/v1/test/**");
        assertThat(cmd.get("tenantId").asText()).isEqualTo(TENANT_ID.toString());
    }

    // ─── Test 2: GET /routes → RabbitMQ RPC → mock reply → HTTP 200 ──────────

    @Test
    @DisplayName("GET /routes sends RabbitMQ query and returns mock route list")
    void listRoutes_returnsRoutesFromMockRabbitReply() throws Exception {
        // Register mock reply for routes.query
        UUID routeId = UUID.randomUUID();
        var mockPage = new QueryResponse.RoutesPage(
                List.of(new QueryResponse.RoutesPage.RouteSummary(
                        routeId, "mock-route", "desc", "/api/mock/**", "GET",
                        "http://mock:8080", RouteStatus.ACTIVE, 1, 0,
                        Instant.now(), Instant.now())),
                1L, 1, 0, 20);

        mockRabbitReply(RabbitTopology.EXCHANGE_ROUTE_SERVICE, RabbitTopology.RK_ROUTES_QUERY,
                request -> mockPage);

        mockMvc.perform(get("/api/v1/admin/routes")
                        .header("Authorization", "Bearer " + viewerJwt())
                        .header(RoutifyHeaders.TENANT_ID, TENANT_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("mock-route"))
                .andExpect(jsonPath("$.content[0].status").value("ACTIVE"));
    }

    // ─── Test 3: GET /routes/{id} → RabbitMQ RPC → mock detail ───────────────

    @Test
    @DisplayName("GET /routes/{id} returns route detail from mock RabbitMQ reply")
    void getRoute_returnsDetailFromMock() throws Exception {
        UUID routeId = UUID.randomUUID();
        var mockDetail = new QueryResponse.RouteDetail(
                routeId, TENANT_ID, "detail-route", "desc", "/api/detail/**",
                "POST", "http://upstream:8080", null, RouteStatus.DRAFT,
                1, List.of(), Map.of(), ACTOR, Instant.now(), null, null);

        mockRabbitReply(RabbitTopology.EXCHANGE_ROUTE_SERVICE, RabbitTopology.RK_ROUTES_GET,
                request -> mockDetail);

        mockMvc.perform(get("/api/v1/admin/routes/" + routeId)
                        .header("Authorization", "Bearer " + viewerJwt())
                        .header(RoutifyHeaders.TENANT_ID, TENANT_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("detail-route"))
                .andExpect(jsonPath("$.id").value(routeId.toString()));
    }

    // ─── Test 4: ActivateRoute → Kafka command + 202 ─────────────────────────

    @Test
    @DisplayName("POST /routes/{id}/activate publishes ActivateRoute command to Kafka")
    void activateRoute_publishesKafkaCommand() throws Exception {
        UUID routeId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/admin/routes/" + routeId + "/activate")
                        .header("Authorization", "Bearer " + operatorJwt())
                        .header(RoutifyHeaders.TENANT_ID, TENANT_ID.toString()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value("Route activation in progress"));

        List<ConsumerRecord<String, String>> records =
                drainTopic(KafkaTopics.ROUTE_COMMANDS, Duration.ofSeconds(10));
        assertThat(records).isNotEmpty();

        JsonNode cmd = objectMapper.readTree(records.get(0).value());
        assertThat(cmd.get("type").asText()).isEqualTo("ACTIVATE_ROUTE");
        assertThat(cmd.get("routeId").asText()).isEqualTo(routeId.toString());
    }

    // ─── Test 5: DeleteRoute → Kafka command + 202 ───────────────────────────

    @Test
    @DisplayName("DELETE /routes/{id} publishes DeleteRoute command to Kafka")
    void deleteRoute_publishesKafkaCommand() throws Exception {
        UUID routeId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/admin/routes/" + routeId)
                        .header("Authorization", "Bearer " + operatorJwt())
                        .header(RoutifyHeaders.TENANT_ID, TENANT_ID.toString()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value("Route deletion in progress"));

        List<ConsumerRecord<String, String>> records =
                drainTopic(KafkaTopics.ROUTE_COMMANDS, Duration.ofSeconds(10));
        assertThat(records).isNotEmpty();

        JsonNode cmd = objectMapper.readTree(records.get(0).value());
        assertThat(cmd.get("type").asText()).isEqualTo("DELETE_ROUTE");
        assertThat(cmd.get("routeId").asText()).isEqualTo(routeId.toString());
    }

    // ─── Test 6: VIEWER cannot create routes (403) ────────────────────────────

    @Test
    @DisplayName("VIEWER role is denied write access to POST /routes → 403")
    void createRoute_viewerForbidden() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "name", "viewer-route",
                "pathPattern", "/api/viewer/**",
                "upstreamUri", "http://upstream:8080"
        ));

        mockMvc.perform(post("/api/v1/admin/routes")
                        .header("Authorization", "Bearer " + viewerJwt())
                        .header(RoutifyHeaders.TENANT_ID, TENANT_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    // ─── Test 7: Unauthenticated request → 401 ──────────────────────────────

    @Test
    @DisplayName("Request without JWT token returns 401")
    void listRoutes_unauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/admin/routes")
                        .header(RoutifyHeaders.TENANT_ID, TENANT_ID.toString()))
                .andExpect(status().isUnauthorized());
    }

    // ─── Test 8: CreateFilter → Kafka FILTER_COMMANDS + 202 ──────────────────

    @Test
    @DisplayName("POST /filters publishes CreateFilter command to Kafka and returns 202")
    void createFilter_publishesKafkaCommandAndReturns202() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "name", "rate-limit-filter",
                "description", "Rate limit to 100 req/s",
                "filterType", "RATE_LIMIT_FIXED_WINDOW",
                "config", Map.of("maxRequests", 100, "windowSeconds", 60)
        ));

        mockMvc.perform(post("/api/v1/admin/filters")
                        .header("Authorization", "Bearer " + operatorJwt())
                        .header(RoutifyHeaders.TENANT_ID, TENANT_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.message").value("Filter creation in progress"));

        List<ConsumerRecord<String, String>> records =
                drainTopic(KafkaTopics.FILTER_COMMANDS, Duration.ofSeconds(10));
        assertThat(records).isNotEmpty();

        JsonNode cmd = objectMapper.readTree(records.get(0).value());
        assertThat(cmd.get("type").asText()).isEqualTo("CREATE_FILTER");
        assertThat(cmd.get("name").asText()).isEqualTo("rate-limit-filter");
        assertThat(cmd.get("filterType").asText()).isEqualTo("RATE_LIMIT_FIXED_WINDOW");
    }

    // ─── Test 9: GET /filters → RabbitMQ RPC → mock reply → 200 ─────────────

    @Test
    @DisplayName("GET /filters returns filter list from mock RabbitMQ reply")
    void listFilters_returnsFiltersFromMock() throws Exception {
        UUID filterId = UUID.randomUUID();
        var mockPage = new QueryResponse.FiltersPage(
                List.of(new QueryResponse.FiltersPage.FilterSummary(
                        filterId, "mock-filter", FilterType.RATE_LIMIT_FIXED_WINDOW,
                        true, 0, null, Instant.now())),
                1L, 1, 0, 20);

        mockRabbitReply(RabbitTopology.EXCHANGE_ROUTE_SERVICE, RabbitTopology.RK_FILTERS_QUERY,
                request -> mockPage);

        mockMvc.perform(get("/api/v1/admin/filters")
                        .header("Authorization", "Bearer " + viewerJwt())
                        .header(RoutifyHeaders.TENANT_ID, TENANT_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("mock-filter"));
    }

    // ─── Test 10: CloneRoute → RabbitMQ sync RPC → 201 ──────────────────────

    @Test
    @DisplayName("POST /routes/{id}/clone returns cloned route from RabbitMQ RPC and 201")
    void cloneRoute_returnsCloneFromRabbitRpc() throws Exception {
        UUID sourceId = UUID.randomUUID();
        UUID cloneId  = UUID.randomUUID();
        var mockClone = new QueryResponse.RouteDetail(
                cloneId, TENANT_ID, "cloned-route (copy)", "cloned desc", "/api/clone/**",
                "GET", "http://upstream:8080", null, RouteStatus.DRAFT,
                1, List.of(), Map.of(), ACTOR, Instant.now(), null, null);

        mockRabbitReply(RabbitTopology.EXCHANGE_ROUTE_SERVICE, RabbitTopology.RK_ROUTES_CLONE,
                request -> mockClone);

        mockMvc.perform(post("/api/v1/admin/routes/" + sourceId + "/clone")
                        .header("Authorization", "Bearer " + operatorJwt())
                        .header(RoutifyHeaders.TENANT_ID, TENANT_ID.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(cloneId.toString()))
                .andExpect(jsonPath("$.name").value("cloned-route (copy)"))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }
}

