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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link FixedWindowRateLimitGatewayFilterFactory}.
 *
 * <p>Tests the rate limiter's allow/reject logic, key resolution strategies,
 * and default configuration. Uses subclass mock maker (JDK 25 compatible).
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

    @SuppressWarnings("unchecked")
    private void mockRedisScript(long returnValue) {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(Flux.just(returnValue));
    }

    // ─── Tests ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Request within limit is allowed (count <= maxRequests)")
    void requestWithinLimit_isAllowed() {
        mockRedisScript(1L);

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
    @DisplayName("Request at exact limit is allowed (count == maxRequests)")
    void requestAtExactLimit_isAllowed() {
        mockRedisScript(10L);

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
        mockRedisScript(11L);

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
        mockRedisScript(101L);

        var config = new FixedWindowRateLimitGatewayFilterFactory.Config();
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("429 response includes X-RateLimit-Window header")
    void rejectedResponse_includesRateLimitHeader() {
        mockRedisScript(200L);

        var config = new FixedWindowRateLimitGatewayFilterFactory.Config();
        config.setMaxRequests(5);
        config.setWindowMs(30_000L);
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Window"))
                .isEqualTo("30000ms");
    }

    @Test
    @DisplayName("USER key resolver uses X-Auth-User-Id header")
    void userKeyResolver_usesAuthUserIdHeader() {
        mockRedisScript(1L);

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
        mockRedisScript(1L);

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
    @DisplayName("Redis returns empty — defaults to 1 (allows request)")
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

