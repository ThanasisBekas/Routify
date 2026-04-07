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
 * Unit tests for {@link SlidingWindowRateLimitGatewayFilterFactory}.
 *
 * <p>Tests the sliding-window rate limiter's allow/reject logic using a mocked Redis interface.
 */
class SlidingWindowRateLimitGatewayFilterFactoryTest {

    private ReactiveStringRedisTemplate redisTemplate;
    private SlidingWindowRateLimitGatewayFilterFactory factory;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(ReactiveStringRedisTemplate.class);
        factory = new SlidingWindowRateLimitGatewayFilterFactory(redisTemplate, new RateLimitKeyResolver());
    }

    private GatewayFilterChain passThroughChain() {
        return exchange -> Mono.empty();
    }

    @SuppressWarnings("unchecked")
    private void mockRedisScript(long returnValue) {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Flux.just(returnValue));
    }

    @Test
    @DisplayName("Request within limit is allowed (Lua returns 1)")
    void requestWithinLimit_isAllowed() {
        mockRedisScript(1L);

        var config = new SlidingWindowRateLimitGatewayFilterFactory.Config();
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
    @DisplayName("Request exceeding limit returns 429 (Lua returns 0)")
    void requestExceedingLimit_returns429() {
        mockRedisScript(0L);

        var config = new SlidingWindowRateLimitGatewayFilterFactory.Config();
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
    @DisplayName("429 response includes X-RateLimit-Window header")
    void rejectedResponse_includesRateLimitWindowHeader() {
        mockRedisScript(0L);

        var config = new SlidingWindowRateLimitGatewayFilterFactory.Config();
        config.setMaxRequests(5);
        config.setWindowMs(30_000L);
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Window"))
                .isEqualTo("30000ms");
    }

    @Test
    @DisplayName("Default config uses 100 max requests and 60s window")
    void defaultConfig_uses100MaxAnd60sWindow() {
        mockRedisScript(0L);

        var config = new SlidingWindowRateLimitGatewayFilterFactory.Config();
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Window"))
                .isEqualTo("60000ms");
    }

    @Test
    @DisplayName("Redis returns empty — defaults to allowed")
    @SuppressWarnings("unchecked")
    void redisReturnsEmpty_allowsRequest() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Flux.empty());

        var config = new SlidingWindowRateLimitGatewayFilterFactory.Config();
        config.setMaxRequests(10);
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }
}
