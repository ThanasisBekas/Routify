package io.routify.gateway.filter;

import io.routify.common.security.RedisKeys;
import io.routify.common.web.RoutifyHeaders;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveHashOperations;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ApiKeyAuthGatewayFilterFactory}.
 *
 * <p>Covers: missing key, invalid key, expired key, valid key with header injection,
 * Redis error fallback, query param extraction, custom header name.
 */
class ApiKeyAuthGatewayFilterFactoryTest {

    private ReactiveStringRedisTemplate redisTemplate;
    private ReactiveHashOperations<String, String, String> hashOps;
    private SimpleMeterRegistry meterRegistry;
    private ApiKeyAuthGatewayFilterFactory factory;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(ReactiveStringRedisTemplate.class);
        hashOps = mock(ReactiveHashOperations.class);
        when(redisTemplate.<String, String>opsForHash()).thenReturn(hashOps);
        meterRegistry = new SimpleMeterRegistry();
        factory = new ApiKeyAuthGatewayFilterFactory(redisTemplate, meterRegistry);
    }

    private GatewayFilterChain passThroughChain() {
        return exchange -> Mono.empty();
    }

    // ─── Missing API Key ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Missing API key header → 401 MISSING_API_KEY")
    void missingApiKey_returns401() {
        var config = new ApiKeyAuthGatewayFilterFactory.Config();
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test").build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Blank API key header → 401 MISSING_API_KEY")
    void blankApiKey_returns401() {
        var config = new ApiKeyAuthGatewayFilterFactory.Config();
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header(RoutifyHeaders.API_KEY, "  ")
                        .build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ─── Invalid API Key ──────────────────────────────────────────────────────

    @Test
    @DisplayName("API key not found in Redis → 401 INVALID_API_KEY")
    void invalidApiKey_returns401() {
        var config = new ApiKeyAuthGatewayFilterFactory.Config();
        GatewayFilter filter = factory.apply(config);

        when(hashOps.entries(RedisKeys.APIKEY_PREFIX + "bad-key-123"))
                .thenReturn(Flux.empty());

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header(RoutifyHeaders.API_KEY, "bad-key-123")
                        .build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ─── Expired API Key ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Expired API key → 401 API_KEY_EXPIRED")
    void expiredApiKey_returns401() {
        var config = new ApiKeyAuthGatewayFilterFactory.Config();
        GatewayFilter filter = factory.apply(config);

        String expiredEpoch = String.valueOf(Instant.now().minusSeconds(3600).getEpochSecond());
        when(hashOps.entries(RedisKeys.APIKEY_PREFIX + "expired-key"))
                .thenReturn(Flux.fromIterable(Map.of(
                        "tenantId", "t1",
                        "userId", "u1",
                        "role", "OPERATOR",
                        "email", "test@example.com",
                        "expiresAt", expiredEpoch
                ).entrySet()));

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header(RoutifyHeaders.API_KEY, "expired-key")
                        .build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ─── Valid API Key ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Valid API key")
    class ValidApiKey {

        @Test
        @DisplayName("Valid key → identity headers injected (X-Auth-User-Id, X-Auth-Tenant-Id, X-Auth-Role, X-Auth-Email, X-Auth-Type)")
        void validApiKey_injectsHeaders() {
            var config = new ApiKeyAuthGatewayFilterFactory.Config();
            GatewayFilter filter = factory.apply(config);

            when(hashOps.entries(RedisKeys.APIKEY_PREFIX + "valid-key-abc"))
                    .thenReturn(Flux.fromIterable(Map.of(
                            "tenantId", "tenant-uuid-1",
                            "userId", "user-uuid-1",
                            "role", "OPERATOR",
                            "email", "op@routify.io"
                    ).entrySet()));

            final String[] capturedHeaders = new String[5];
            GatewayFilterChain capturingChain = ex -> {
                capturedHeaders[0] = ex.getRequest().getHeaders().getFirst(RoutifyHeaders.AUTH_USER_ID);
                capturedHeaders[1] = ex.getRequest().getHeaders().getFirst(RoutifyHeaders.AUTH_TENANT_ID);
                capturedHeaders[2] = ex.getRequest().getHeaders().getFirst(RoutifyHeaders.AUTH_ROLE);
                capturedHeaders[3] = ex.getRequest().getHeaders().getFirst(RoutifyHeaders.AUTH_EMAIL);
                capturedHeaders[4] = ex.getRequest().getHeaders().getFirst(RoutifyHeaders.AUTH_TYPE);
                return Mono.empty();
            };

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test")
                            .header(RoutifyHeaders.API_KEY, "valid-key-abc")
                            .build());

            StepVerifier.create(filter.filter(exchange, capturingChain))
                    .verifyComplete();

            assertThat(capturedHeaders[0]).isEqualTo("user-uuid-1");
            assertThat(capturedHeaders[1]).isEqualTo("tenant-uuid-1");
            assertThat(capturedHeaders[2]).isEqualTo("OPERATOR");
            assertThat(capturedHeaders[3]).isEqualTo("op@routify.io");
            assertThat(capturedHeaders[4]).isEqualTo("API_KEY");
        }

        @Test
        @DisplayName("Valid key without expiresAt → passes through (no expiry check)")
        void validKeyWithoutExpiry_passesThrough() {
            var config = new ApiKeyAuthGatewayFilterFactory.Config();
            GatewayFilter filter = factory.apply(config);

            when(hashOps.entries(RedisKeys.APIKEY_PREFIX + "no-expiry-key"))
                    .thenReturn(Flux.fromIterable(Map.of(
                            "tenantId", "t1",
                            "userId", "u1",
                            "role", "VIEWER",
                            "email", "viewer@test.com"
                    ).entrySet()));

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test")
                            .header(RoutifyHeaders.API_KEY, "no-expiry-key")
                            .build());

            StepVerifier.create(filter.filter(exchange, passThroughChain()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isNull();
        }

        @Test
        @DisplayName("Valid key with future expiresAt → passes through")
        void validKeyWithFutureExpiry_passesThrough() {
            var config = new ApiKeyAuthGatewayFilterFactory.Config();
            GatewayFilter filter = factory.apply(config);

            String futureEpoch = String.valueOf(Instant.now().plusSeconds(86400).getEpochSecond());
            when(hashOps.entries(RedisKeys.APIKEY_PREFIX + "future-key"))
                    .thenReturn(Flux.fromIterable(Map.of(
                            "tenantId", "t1",
                            "userId", "u1",
                            "role", "OPERATOR",
                            "email", "op@test.com",
                            "expiresAt", futureEpoch
                    ).entrySet()));

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/test")
                            .header(RoutifyHeaders.API_KEY, "future-key")
                            .build());

            StepVerifier.create(filter.filter(exchange, passThroughChain()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isNull();
        }
    }

    // ─── Redis Error ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Redis lookup failure → 502 API_KEY_VALIDATION_FAILED")
    void redisError_returns502() {
        var config = new ApiKeyAuthGatewayFilterFactory.Config();
        GatewayFilter filter = factory.apply(config);

        when(hashOps.entries(anyString()))
                .thenReturn(Flux.error(new RuntimeException("Redis connection refused")));

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header(RoutifyHeaders.API_KEY, "some-key")
                        .build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    // ─── Query Param Extraction ───────────────────────────────────────────────

    @Test
    @DisplayName("API key from query parameter when header is absent")
    void queryParamFallback_extractsKey() {
        var config = new ApiKeyAuthGatewayFilterFactory.Config();
        config.setQueryParam("api_key");
        GatewayFilter filter = factory.apply(config);

        when(hashOps.entries(RedisKeys.APIKEY_PREFIX + "qp-key-123"))
                .thenReturn(Flux.fromIterable(Map.of(
                        "tenantId", "t1",
                        "userId", "u1",
                        "role", "VIEWER",
                        "email", "v@test.com"
                ).entrySet()));

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test?api_key=qp-key-123").build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    // ─── Custom Header Name ───────────────────────────────────────────────────

    @Test
    @DisplayName("Custom header name is respected")
    void customHeaderName_readsFromCustomHeader() {
        var config = new ApiKeyAuthGatewayFilterFactory.Config();
        config.setHeaderName("Authorization-Key");
        GatewayFilter filter = factory.apply(config);

        when(hashOps.entries(RedisKeys.APIKEY_PREFIX + "custom-key"))
                .thenReturn(Flux.fromIterable(Map.of(
                        "tenantId", "t1",
                        "userId", "u1",
                        "role", "OPERATOR",
                        "email", "op@test.com"
                ).entrySet()));

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header("Authorization-Key", "custom-key")
                        .build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    // ─── Metrics ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Auth failures increment the routify.auth.failures counter with method=API_KEY tag")
    void authFailure_incrementsCounter() {
        var config = new ApiKeyAuthGatewayFilterFactory.Config();
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test").build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        var counter = meterRegistry.find("routify.auth.failures")
                .tag("method", "API_KEY")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isGreaterThanOrEqualTo(1.0);
    }
}

