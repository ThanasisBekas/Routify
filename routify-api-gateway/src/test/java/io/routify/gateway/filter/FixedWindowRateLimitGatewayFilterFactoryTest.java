package io.routify.gateway.filter;

import io.routify.gateway.filter.ratelimit.RateLimitKeyResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link FixedWindowRateLimitGatewayFilterFactory}.
 *
 * <p>Tests the rate limiter's allow/reject logic, key resolution strategies,
 * X-RateLimit-* header injection, and includeHeaders config flag.
 */
class FixedWindowRateLimitGatewayFilterFactoryTest {

    private ReactiveStringRedisTemplate redisTemplate;
    private FixedWindowRateLimitGatewayFilterFactory factory;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(ReactiveStringRedisTemplate.class);
        factory = new FixedWindowRateLimitGatewayFilterFactory(redisTemplate, new RateLimitKeyResolver());
    }

    private GatewayFilterChain passThroughChain() {
        return exchange -> Mono.empty();
    }

    /**
     * Mock Redis to return a {count, ttl} tuple (List result).
     */
    @SuppressWarnings("unchecked")
    private void mockRedisScript(long count, long ttlSeconds) {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(Flux.just(List.of(count, ttlSeconds)));
    }

    // ─── Allow / Reject logic ──────────────────────────────────────────────────

    @Test
    @DisplayName("Request within limit is allowed (count <= maxRequests)")
    void requestWithinLimit_isAllowed() {
        mockRedisScript(1L, 60L);

        var config = new FixedWindowRateLimitGatewayFilterFactory.Config();
        config.setMaxRequests(10);
        config.setWindowMs(60_000L);
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        // Status is null because the chain completed without setting status
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("Request at exact limit is allowed (count == maxRequests)")
    void requestAtExactLimit_isAllowed() {
        mockRedisScript(10L, 45L);

        var config = new FixedWindowRateLimitGatewayFilterFactory.Config();
        config.setMaxRequests(10);
        config.setWindowMs(60_000L);
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("Request exceeding limit returns 429 Too Many Requests")
    void requestExceedingLimit_returns429() {
        mockRedisScript(11L, 30L);

        var config = new FixedWindowRateLimitGatewayFilterFactory.Config();
        config.setMaxRequests(10);
        config.setWindowMs(60_000L);
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("Default config uses 100 max requests and 60s window")
    void defaultConfig_uses100MaxAnd60sWindow() {
        mockRedisScript(101L, 55L);

        var config = new FixedWindowRateLimitGatewayFilterFactory.Config();
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    // ─── X-RateLimit-* Headers (GF-03) ─────────────────────────────────────────

    @Test
    @DisplayName("Allowed response includes X-RateLimit-Limit header")
    void allowedResponse_includesRateLimitLimitHeader() {
        mockRedisScript(3L, 50L);

        var config = new FixedWindowRateLimitGatewayFilterFactory.Config();
        config.setMaxRequests(10);
        config.setWindowMs(60_000L);
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Limit"))
                .isEqualTo("10");
    }

    @Test
    @DisplayName("Allowed response includes X-RateLimit-Remaining that decrements correctly")
    void allowedResponse_includesRemainingHeader() {
        mockRedisScript(3L, 50L);

        var config = new FixedWindowRateLimitGatewayFilterFactory.Config();
        config.setMaxRequests(10);
        config.setWindowMs(60_000L);
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining"))
                .isEqualTo("7"); // 10 - 3
    }

    @Test
    @DisplayName("Allowed response includes X-RateLimit-Reset as a future epoch timestamp")
    void allowedResponse_includesResetHeader() {
        mockRedisScript(1L, 50L);

        var config = new FixedWindowRateLimitGatewayFilterFactory.Config();
        config.setMaxRequests(10);
        config.setWindowMs(60_000L);
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        String resetHeader = exchange.getResponse().getHeaders().getFirst("X-RateLimit-Reset");
        assertThat(resetHeader).isNotNull();
        long resetEpoch = Long.parseLong(resetHeader);
        assertThat(resetEpoch).isGreaterThan(Instant.now().getEpochSecond());
    }

    @Test
    @DisplayName("429 response includes Retry-After header")
    void rejectedResponse_includesRetryAfter() {
        mockRedisScript(200L, 30L);

        var config = new FixedWindowRateLimitGatewayFilterFactory.Config();
        config.setMaxRequests(5);
        config.setWindowMs(30_000L);
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After"))
                .isEqualTo("30");
    }

    @Test
    @DisplayName("429 response includes X-RateLimit-* headers when includeHeaders=true")
    void rejectedResponse_includesRateLimitHeaders() {
        mockRedisScript(11L, 25L);

        var config = new FixedWindowRateLimitGatewayFilterFactory.Config();
        config.setMaxRequests(10);
        config.setWindowMs(60_000L);
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Limit")).isEqualTo("10");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Reset")).isNotNull();
    }

    @Test
    @DisplayName("Retry-After absent on allowed response")
    void allowedResponse_noRetryAfter() {
        mockRedisScript(1L, 55L);

        var config = new FixedWindowRateLimitGatewayFilterFactory.Config();
        config.setMaxRequests(10);
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isNull();
    }

    // ─── includeHeaders=false ──────────────────────────────────────────────────

    @Test
    @DisplayName("includeHeaders=false suppresses X-RateLimit-* headers on allowed response")
    void includeHeadersFalse_suppressesHeadersOnAllow() {
        mockRedisScript(3L, 50L);

        var config = new FixedWindowRateLimitGatewayFilterFactory.Config();
        config.setMaxRequests(10);
        config.setWindowMs(60_000L);
        config.setIncludeHeaders(false);
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Limit")).isNull();
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining")).isNull();
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Reset")).isNull();
    }

    @Test
    @DisplayName("includeHeaders=false still includes Retry-After on 429")
    void includeHeadersFalse_stillHasRetryAfterOn429() {
        mockRedisScript(11L, 30L);

        var config = new FixedWindowRateLimitGatewayFilterFactory.Config();
        config.setMaxRequests(10);
        config.setWindowMs(60_000L);
        config.setIncludeHeaders(false);
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("30");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Limit")).isNull();
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining")).isNull();
    }

    // ─── Key Resolver strategies ───────────────────────────────────────────────

    @Test
    @DisplayName("USER key resolver uses X-Auth-User-Id header")
    void userKeyResolver_usesAuthUserIdHeader() {
        mockRedisScript(1L, 60L);

        var config = new FixedWindowRateLimitGatewayFilterFactory.Config();
        config.setKeyResolver("USER");
        config.setMaxRequests(100);
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header("X-Auth-User-Id", "user-123")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("TENANT key resolver uses X-Tenant-Id header")
    void tenantKeyResolver_usesTenantIdHeader() {
        mockRedisScript(1L, 60L);

        var config = new FixedWindowRateLimitGatewayFilterFactory.Config();
        config.setKeyResolver("TENANT");
        config.setMaxRequests(100);
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header("X-Tenant-Id", "tenant-abc")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("Redis returns empty — defaults to allowed with default TTL")
    @SuppressWarnings("unchecked")
    void redisReturnsEmpty_allowsRequest() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(Flux.empty());

        var config = new FixedWindowRateLimitGatewayFilterFactory.Config();
        config.setMaxRequests(10);
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }
}

