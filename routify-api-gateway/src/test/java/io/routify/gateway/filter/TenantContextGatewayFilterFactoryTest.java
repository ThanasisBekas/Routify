package io.routify.gateway.filter;

import io.routify.common.domain.TenantPlan;
import io.routify.common.observability.RoutifyMetrics;
import io.routify.common.web.RoutifyHeaders;
import io.routify.gateway.config.GatewayConfigLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TenantContextGatewayFilterFactory}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Caller-provided mode (enabled=true): tenant from JWT, from header, mismatch → 403</li>
 *   <li>Auto-inject mode (enabled=false): tenant from route metadata, from header/JWT</li>
 *   <li>Monthly quota enforcement via Redis</li>
 *   <li>Exchange attribute injection</li>
 *   <li>Filter order (+100)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class TenantContextGatewayFilterFactoryTest {

    @Mock
    private GatewayConfigLoader configLoader;

    @Mock
    private ReactiveStringRedisTemplate redisTemplate;

    @Mock
    private ReactiveValueOperations<String, String> valueOps;

    @Mock
    private GatewayTenantPlanCache tenantPlanCache;

    @Mock
    private RoutifyMetrics metrics;

    private TenantContextGatewayFilterFactory factory;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final String TENANT_ID_STR = TENANT_ID.toString();

    @BeforeEach
    void setup() {
        factory = new TenantContextGatewayFilterFactory(
                configLoader, redisTemplate, tenantPlanCache, metrics, false);
    }

    /** Creates a factory with quota enforcement enabled. */
    private TenantContextGatewayFilterFactory quotaEnabledFactory() {
        return new TenantContextGatewayFilterFactory(
                configLoader, redisTemplate, tenantPlanCache, metrics, true);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Caller-provided mode (enabled=true)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Caller-provided mode (enabled=true)")
    class CallerProvidedMode {

        @BeforeEach
        void setupCallerMode() {
            when(configLoader.getConfig()).thenReturn(Map.of(
                    "tenantIsolation", Map.of("enabled", true, "tenantIdHeader", "X-Tenant-Id")
            ));
        }

        @Test
        @DisplayName("JWT tenant → propagated as X-Tenant-Id + stored as exchange attribute")
        void jwtTenant_propagated() {
            GatewayFilter filter = factory.apply(new TenantContextGatewayFilterFactory.Config());

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test")
                            .header(RoutifyHeaders.AUTH_TENANT_ID, TENANT_ID_STR)
                            .build());

            ServerHttpRequest[] captured = {null};
            StepVerifier.create(filter.filter(exchange, ex -> {
                captured[0] = ex.getRequest();
                return Mono.empty();
            })).verifyComplete();

            assertThat(captured[0]).isNotNull();
            assertThat(captured[0].getHeaders().getFirst(RoutifyHeaders.TENANT_ID))
                    .isEqualTo(TENANT_ID_STR);
            assertThat((String) exchange.getAttribute(TenantContextGatewayFilterFactory.ATTR_TENANT_ID))
                    .isEqualTo(TENANT_ID_STR);
        }

        @Test
        @DisplayName("Caller header → propagated when no JWT")
        void callerHeader_propagated() {
            GatewayFilter filter = factory.apply(new TenantContextGatewayFilterFactory.Config());

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test")
                            .header(RoutifyHeaders.TENANT_ID, TENANT_ID_STR)
                            .build());

            ServerHttpRequest[] captured = {null};
            StepVerifier.create(filter.filter(exchange, ex -> {
                captured[0] = ex.getRequest();
                return Mono.empty();
            })).verifyComplete();

            assertThat(captured[0]).isNotNull();
            assertThat(captured[0].getHeaders().getFirst(RoutifyHeaders.TENANT_ID))
                    .isEqualTo(TENANT_ID_STR);
        }

        @Test
        @DisplayName("JWT and caller header match → no mismatch error")
        void matchingJwtAndCaller_noError() {
            GatewayFilter filter = factory.apply(new TenantContextGatewayFilterFactory.Config());

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test")
                            .header(RoutifyHeaders.AUTH_TENANT_ID, TENANT_ID_STR)
                            .header(RoutifyHeaders.TENANT_ID, TENANT_ID_STR)
                            .build());

            ServerHttpRequest[] captured = {null};
            StepVerifier.create(filter.filter(exchange, ex -> {
                captured[0] = ex.getRequest();
                return Mono.empty();
            })).verifyComplete();

            assertThat(captured[0]).isNotNull();
        }

        @Test
        @DisplayName("JWT and caller header MISMATCH → 403 TENANT_MISMATCH")
        void mismatchJwtAndCaller_returns403() {
            GatewayFilter filter = factory.apply(new TenantContextGatewayFilterFactory.Config());

            UUID otherTenant = UUID.randomUUID();
            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test")
                            .header(RoutifyHeaders.AUTH_TENANT_ID, TENANT_ID_STR)
                            .header(RoutifyHeaders.TENANT_ID, otherTenant.toString())
                            .build());

            StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("No tenant info at all → pass-through (no crash)")
        void noTenantInfo_passesThrough() {
            GatewayFilter filter = factory.apply(new TenantContextGatewayFilterFactory.Config());

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test").build());

            boolean[] chainCalled = {false};
            StepVerifier.create(filter.filter(exchange, ex -> {
                chainCalled[0] = true;
                return Mono.empty();
            })).verifyComplete();

            assertThat(chainCalled[0]).isTrue();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Auto-inject mode (enabled=false)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Auto-inject mode (enabled=false)")
    class AutoInjectMode {

        @BeforeEach
        void setupAutoInjectMode() {
            when(configLoader.getConfig()).thenReturn(Map.of(
                    "tenantIsolation", Map.of("enabled", false)
            ));
        }

        @Test
        @DisplayName("Route metadata tenantId → auto-injected as X-Tenant-Id")
        void routeMetadata_autoInjected() {
            GatewayFilter filter = factory.apply(new TenantContextGatewayFilterFactory.Config());

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test").build());

            // Simulate SCG route with tenantId in metadata
            Route route = Route.async()
                    .id("test-route")
                    .uri(URI.create("http://localhost"))
                    .predicate(ex -> true)
                    .metadata("tenantId", TENANT_ID_STR)
                    .build();
            exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route);

            ServerHttpRequest[] captured = {null};
            StepVerifier.create(filter.filter(exchange, ex -> {
                captured[0] = ex.getRequest();
                return Mono.empty();
            })).verifyComplete();

            assertThat(captured[0]).isNotNull();
            assertThat(captured[0].getHeaders().getFirst(RoutifyHeaders.TENANT_ID))
                    .isEqualTo(TENANT_ID_STR);
            assertThat((String) exchange.getAttribute(TenantContextGatewayFilterFactory.ATTR_TENANT_ID))
                    .isEqualTo(TENANT_ID_STR);
        }

        @Test
        @DisplayName("JWT tenant takes priority over route metadata in auto-inject mode")
        void jwtTakesPriority_inAutoInject() {
            GatewayFilter filter = factory.apply(new TenantContextGatewayFilterFactory.Config());

            UUID jwtTenantId = UUID.randomUUID();
            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test")
                            .header(RoutifyHeaders.AUTH_TENANT_ID, jwtTenantId.toString())
                            .build());

            // Route also has a tenant in metadata
            Route route = Route.async()
                    .id("test-route")
                    .uri(URI.create("http://localhost"))
                    .predicate(ex -> true)
                    .metadata("tenantId", TENANT_ID_STR)
                    .build();
            exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route);

            ServerHttpRequest[] captured = {null};
            StepVerifier.create(filter.filter(exchange, ex -> {
                captured[0] = ex.getRequest();
                return Mono.empty();
            })).verifyComplete();

            // JWT tenant should win
            assertThat(captured[0].getHeaders().getFirst(RoutifyHeaders.TENANT_ID))
                    .isEqualTo(jwtTenantId.toString());
        }

        @Test
        @DisplayName("No tenant info and no route metadata → pass-through")
        void noTenantInfo_passesThrough() {
            GatewayFilter filter = factory.apply(new TenantContextGatewayFilterFactory.Config());

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test").build());

            boolean[] chainCalled = {false};
            StepVerifier.create(filter.filter(exchange, ex -> {
                chainCalled[0] = true;
                return Mono.empty();
            })).verifyComplete();

            assertThat(chainCalled[0]).isTrue();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Quota enforcement
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Monthly quota enforcement")
    class QuotaEnforcement {

        @BeforeEach
        void setupQuotaMode() {
            when(configLoader.getConfig()).thenReturn(Map.of(
                    "tenantIsolation", Map.of("enabled", true)
            ));
        }

        @Test
        @DisplayName("ENTERPRISE plan (unlimited) → skips quota check")
        void enterprisePlan_skipsQuota() {
            var quotaFactory = quotaEnabledFactory();
            GatewayFilter filter = quotaFactory.apply(new TenantContextGatewayFilterFactory.Config());

            when(tenantPlanCache.getPlan(TENANT_ID)).thenReturn(TenantPlan.ENTERPRISE);

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test")
                            .header(RoutifyHeaders.AUTH_TENANT_ID, TENANT_ID_STR)
                            .build());

            ServerHttpRequest[] captured = {null};
            StepVerifier.create(filter.filter(exchange, ex -> {
                captured[0] = ex.getRequest();
                return Mono.empty();
            })).verifyComplete();

            assertThat(captured[0]).isNotNull();
        }

        @Test
        @DisplayName("Quota within limit → passes through")
        void withinQuota_passesThrough() {
            var quotaFactory = quotaEnabledFactory();
            GatewayFilter filter = quotaFactory.apply(new TenantContextGatewayFilterFactory.Config());

            when(tenantPlanCache.getPlan(TENANT_ID)).thenReturn(TenantPlan.PRO); // 100k quota
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.increment(anyString())).thenReturn(Mono.just(50L));

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test")
                            .header(RoutifyHeaders.AUTH_TENANT_ID, TENANT_ID_STR)
                            .build());

            ServerHttpRequest[] captured = {null};
            StepVerifier.create(filter.filter(exchange, ex -> {
                captured[0] = ex.getRequest();
                return Mono.empty();
            })).verifyComplete();

            assertThat(captured[0]).isNotNull();
        }

        @Test
        @DisplayName("Quota exceeded → 429 QUOTA_EXCEEDED")
        void quotaExceeded_returns429() {
            var quotaFactory = quotaEnabledFactory();
            GatewayFilter filter = quotaFactory.apply(new TenantContextGatewayFilterFactory.Config());

            when(tenantPlanCache.getPlan(TENANT_ID)).thenReturn(TenantPlan.FREE); // 1k quota
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.increment(anyString())).thenReturn(Mono.just(1001L));

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test")
                            .header(RoutifyHeaders.AUTH_TENANT_ID, TENANT_ID_STR)
                            .build());

            StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }

        @Test
        @DisplayName("Redis error → fail open (request passes through)")
        void redisError_failOpen() {
            var quotaFactory = quotaEnabledFactory();
            GatewayFilter filter = quotaFactory.apply(new TenantContextGatewayFilterFactory.Config());

            when(tenantPlanCache.getPlan(TENANT_ID)).thenReturn(TenantPlan.PRO);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.increment(anyString())).thenReturn(Mono.error(new RuntimeException("Redis down")));

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test")
                            .header(RoutifyHeaders.AUTH_TENANT_ID, TENANT_ID_STR)
                            .build());

            ServerHttpRequest[] captured = {null};
            StepVerifier.create(filter.filter(exchange, ex -> {
                captured[0] = ex.getRequest();
                return Mono.empty();
            })).verifyComplete();

            // Should pass through despite Redis failure
            assertThat(captured[0]).isNotNull();
        }

        @Test
        @DisplayName("Non-UUID tenant ID → skips quota enforcement")
        void nonUuidTenant_skipsQuota() {
            var quotaFactory = quotaEnabledFactory();
            GatewayFilter filter = quotaFactory.apply(new TenantContextGatewayFilterFactory.Config());

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test")
                            .header(RoutifyHeaders.AUTH_TENANT_ID, "not-a-uuid")
                            .build());

            ServerHttpRequest[] captured = {null};
            StepVerifier.create(filter.filter(exchange, ex -> {
                captured[0] = ex.getRequest();
                return Mono.empty();
            })).verifyComplete();

            // Should pass through without touching Redis
            assertThat(captured[0]).isNotNull();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Gateway config edge cases
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Gateway config edge cases")
    class ConfigEdgeCases {

        @Test
        @DisplayName("Null gateway config → defaults to enabled=true")
        void nullGatewayConfig_defaultsEnabled() {
            when(configLoader.getConfig()).thenReturn(null);

            GatewayFilter filter = factory.apply(new TenantContextGatewayFilterFactory.Config());

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test")
                            .header(RoutifyHeaders.AUTH_TENANT_ID, TENANT_ID_STR)
                            .build());

            ServerHttpRequest[] captured = {null};
            StepVerifier.create(filter.filter(exchange, ex -> {
                captured[0] = ex.getRequest();
                return Mono.empty();
            })).verifyComplete();

            assertThat(captured[0]).isNotNull();
            assertThat(captured[0].getHeaders().getFirst(RoutifyHeaders.TENANT_ID))
                    .isEqualTo(TENANT_ID_STR);
        }

        @Test
        @DisplayName("Empty gateway config → defaults to enabled=true")
        void emptyGatewayConfig_defaultsEnabled() {
            when(configLoader.getConfig()).thenReturn(Map.of());

            GatewayFilter filter = factory.apply(new TenantContextGatewayFilterFactory.Config());

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test")
                            .header(RoutifyHeaders.TENANT_ID, TENANT_ID_STR)
                            .build());

            ServerHttpRequest[] captured = {null};
            StepVerifier.create(filter.filter(exchange, ex -> {
                captured[0] = ex.getRequest();
                return Mono.empty();
            })).verifyComplete();

            assertThat(captured[0]).isNotNull();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Filter order
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Filter order is +100 (after JWT auth)")
    void filterOrder_isPlus100() {
        GatewayFilter filter = factory.apply(new TenantContextGatewayFilterFactory.Config());
        assertThat(filter).isInstanceOf(org.springframework.core.Ordered.class);
        assertThat(((org.springframework.core.Ordered) filter).getOrder()).isEqualTo(100);
    }
}

