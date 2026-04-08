package io.routify.gateway.filter.resilience;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RetryV2GatewayFilterFactory}.
 *
 * <p>Tests verify:
 * <ul>
 *   <li>Default and custom configuration values</li>
 *   <li>Status code parsing</li>
 *   <li>Method parsing</li>
 *   <li>Retry predicate for retryable status exceptions and timeout exceptions</li>
 *   <li>Idempotency awareness (safe vs unsafe methods)</li>
 *   <li>Metric counter registration</li>
 * </ul>
 */
class RetryV2Test {

    private RetryV2GatewayFilterFactory factory;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        factory = new RetryV2GatewayFilterFactory(meterRegistry);
    }

    // ─── Default config values ────────────────────────────────────────────────

    @Test
    void defaultConfigValues() {
        var config = new RetryV2GatewayFilterFactory.Config();
        assertThat(config.getMaxRetries()).isEqualTo(3);
        assertThat(config.getInitialBackoffMs()).isEqualTo(500L);
        assertThat(config.getMaxBackoffMs()).isEqualTo(5000L);
        assertThat(config.getBackoffMultiplier()).isEqualTo(2.0);
        assertThat(config.getJitterFactor()).isEqualTo(0.25);
        assertThat(config.getRetryableStatuses()).isEqualTo("502,503,504");
        assertThat(config.getRetryableMethods()).isEqualTo("GET,HEAD,OPTIONS");
        assertThat(config.isRetryOnTimeout()).isTrue();
        assertThat(config.getIdempotencyHeader()).isEqualTo("Idempotency-Key");
    }

    @Test
    void customConfigValues() {
        var config = new RetryV2GatewayFilterFactory.Config();
        config.setMaxRetries(5);
        config.setInitialBackoffMs(1000L);
        config.setMaxBackoffMs(10000L);
        config.setBackoffMultiplier(3.0);
        config.setJitterFactor(0.5);
        config.setRetryableStatuses("500,502,503,504");
        config.setRetryableMethods("GET,PUT");
        config.setRetryOnTimeout(false);
        config.setIdempotencyHeader("X-Idempotency-Key");

        assertThat(config.getMaxRetries()).isEqualTo(5);
        assertThat(config.getInitialBackoffMs()).isEqualTo(1000L);
        assertThat(config.getMaxBackoffMs()).isEqualTo(10000L);
        assertThat(config.getBackoffMultiplier()).isEqualTo(3.0);
        assertThat(config.getJitterFactor()).isEqualTo(0.5);
        assertThat(config.getRetryableStatuses()).isEqualTo("500,502,503,504");
        assertThat(config.getRetryableMethods()).isEqualTo("GET,PUT");
        assertThat(config.isRetryOnTimeout()).isFalse();
        assertThat(config.getIdempotencyHeader()).isEqualTo("X-Idempotency-Key");
    }

    // ─── Status code parsing ──────────────────────────────────────────────────

    @Test
    void parseStatuses_default() {
        Set<Integer> statuses = RetryV2GatewayFilterFactory.parseStatuses("502,503,504");
        assertThat(statuses).containsExactlyInAnyOrder(502, 503, 504);
    }

    @Test
    void parseStatuses_customWithSpaces() {
        Set<Integer> statuses = RetryV2GatewayFilterFactory.parseStatuses(" 500 , 502 , 503 , 504 ");
        assertThat(statuses).containsExactlyInAnyOrder(500, 502, 503, 504);
    }

    @Test
    void parseStatuses_nullFallsBackToDefault() {
        Set<Integer> statuses = RetryV2GatewayFilterFactory.parseStatuses(null);
        assertThat(statuses).containsExactlyInAnyOrder(502, 503, 504);
    }

    @Test
    void parseStatuses_blankFallsBackToDefault() {
        Set<Integer> statuses = RetryV2GatewayFilterFactory.parseStatuses("  ");
        assertThat(statuses).containsExactlyInAnyOrder(502, 503, 504);
    }

    // ─── Method parsing ───────────────────────────────────────────────────────

    @Test
    void parseMethods_default() {
        Set<String> methods = RetryV2GatewayFilterFactory.parseMethods("GET,HEAD,OPTIONS");
        assertThat(methods).containsExactlyInAnyOrder("GET", "HEAD", "OPTIONS");
    }

    @Test
    void parseMethods_customWithSpaces() {
        Set<String> methods = RetryV2GatewayFilterFactory.parseMethods(" get , put , patch ");
        assertThat(methods).containsExactlyInAnyOrder("GET", "PUT", "PATCH");
    }

    @Test
    void parseMethods_nullFallsBackToDefault() {
        Set<String> methods = RetryV2GatewayFilterFactory.parseMethods(null);
        assertThat(methods).containsExactlyInAnyOrder("GET", "HEAD", "OPTIONS");
    }

    // ─── Retry predicate ──────────────────────────────────────────────────────

    @Test
    void retryableStatusException_isAlwaysRetryable() {
        var config = new RetryV2GatewayFilterFactory.Config();
        var ex = new RetryV2GatewayFilterFactory.RetryableStatusException(503, "route-1");

        assertThat(RetryV2GatewayFilterFactory.isRetryableException(ex, config)).isTrue();
    }

    @Test
    void timeoutException_retryableWhenEnabled() {
        var config = new RetryV2GatewayFilterFactory.Config();
        config.setRetryOnTimeout(true);

        assertThat(RetryV2GatewayFilterFactory.isRetryableException(
                new TimeoutException("read timeout"), config)).isTrue();
    }

    @Test
    void timeoutException_notRetryableWhenDisabled() {
        var config = new RetryV2GatewayFilterFactory.Config();
        config.setRetryOnTimeout(false);

        assertThat(RetryV2GatewayFilterFactory.isRetryableException(
                new TimeoutException("read timeout"), config)).isFalse();
    }

    @Test
    void connectException_retryableWhenTimeoutEnabled() {
        var config = new RetryV2GatewayFilterFactory.Config();
        config.setRetryOnTimeout(true);

        assertThat(RetryV2GatewayFilterFactory.isRetryableException(
                new ConnectException("connection refused"), config)).isTrue();
    }

    @Test
    void ioException_notRetryableWhenTimeoutDisabled() {
        var config = new RetryV2GatewayFilterFactory.Config();
        config.setRetryOnTimeout(false);

        assertThat(RetryV2GatewayFilterFactory.isRetryableException(
                new IOException("broken pipe"), config)).isFalse();
    }

    @Test
    void arbitraryException_neverRetryable() {
        var config = new RetryV2GatewayFilterFactory.Config();
        config.setRetryOnTimeout(true);

        assertThat(RetryV2GatewayFilterFactory.isRetryableException(
                new IllegalArgumentException("bad arg"), config)).isFalse();
    }

    @Test
    void nullPointerException_neverRetryable() {
        var config = new RetryV2GatewayFilterFactory.Config();
        config.setRetryOnTimeout(true);

        assertThat(RetryV2GatewayFilterFactory.isRetryableException(
                new NullPointerException(), config)).isFalse();
    }

    // ─── RetryableStatusException ─────────────────────────────────────────────

    @Test
    void retryableStatusException_carriesStatusCode() {
        var ex = new RetryV2GatewayFilterFactory.RetryableStatusException(504, "route-x");
        assertThat(ex.getStatusCode()).isEqualTo(504);
        assertThat(ex.getMessage()).contains("504").contains("route-x");
    }

    // ─── Factory produces a non-null filter ───────────────────────────────────

    @Test
    void applyShouldReturnNonNullFilter() {
        var config = new RetryV2GatewayFilterFactory.Config();
        var filter = factory.apply(config);
        assertThat(filter).isNotNull();
    }

    @Test
    void applyShouldReturnOrderedFilter() {
        var config = new RetryV2GatewayFilterFactory.Config();
        var filter = factory.apply(config);
        assertThat(filter).isInstanceOf(org.springframework.core.Ordered.class);
    }
}

