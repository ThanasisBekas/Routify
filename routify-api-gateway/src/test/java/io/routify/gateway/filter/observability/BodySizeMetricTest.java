package io.routify.gateway.filter.observability;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BodySizeMetricGatewayFilterFactory}.
 *
 * <p>Tests verify:
 * <ul>
 *   <li>Default and custom configuration values</li>
 *   <li>Content-Length resolution helper</li>
 *   <li>Filter factory produces non-null, ordered filter</li>
 *   <li>Config flags control metric inclusion</li>
 * </ul>
 */
class BodySizeMetricTest {

    private BodySizeMetricGatewayFilterFactory factory;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        factory = new BodySizeMetricGatewayFilterFactory(meterRegistry);
    }

    // ─── Default config ───────────────────────────────────────────────────────

    @Test
    void defaultConfigValues() {
        var config = new BodySizeMetricGatewayFilterFactory.Config();
        assertThat(config.isIncludeRequest()).isTrue();
        assertThat(config.isIncludeResponse()).isTrue();
        assertThat(config.getTags()).isNull();
    }

    @Test
    void customConfigValues() {
        var config = new BodySizeMetricGatewayFilterFactory.Config();
        config.setIncludeRequest(false);
        config.setIncludeResponse(false);
        config.setTags(Map.of("env", "staging", "region", "eu-west"));

        assertThat(config.isIncludeRequest()).isFalse();
        assertThat(config.isIncludeResponse()).isFalse();
        assertThat(config.getTags()).containsEntry("env", "staging");
        assertThat(config.getTags()).containsEntry("region", "eu-west");
    }

    // ─── Content-Length resolution ─────────────────────────────────────────────

    @Nested
    class ContentLengthResolution {

        @Test
        void resolveContentLength_present() {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentLength(1024);
            assertThat(BodySizeMetricGatewayFilterFactory.resolveContentLength(headers)).isEqualTo(1024);
        }

        @Test
        void resolveContentLength_absent() {
            HttpHeaders headers = new HttpHeaders();
            assertThat(BodySizeMetricGatewayFilterFactory.resolveContentLength(headers)).isEqualTo(-1);
        }

        @Test
        void resolveContentLength_zero() {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentLength(0);
            assertThat(BodySizeMetricGatewayFilterFactory.resolveContentLength(headers)).isEqualTo(0);
        }

        @Test
        void resolveContentLength_largeValue() {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentLength(10_000_000L);
            assertThat(BodySizeMetricGatewayFilterFactory.resolveContentLength(headers)).isEqualTo(10_000_000L);
        }
    }

    // ─── Filter factory ───────────────────────────────────────────────────────

    @Test
    void applyShouldReturnNonNullFilter() {
        var config = new BodySizeMetricGatewayFilterFactory.Config();
        var filter = factory.apply(config);
        assertThat(filter).isNotNull();
    }

    @Test
    void applyShouldReturnOrderedFilter() {
        var config = new BodySizeMetricGatewayFilterFactory.Config();
        var filter = factory.apply(config);
        assertThat(filter).isInstanceOf(org.springframework.core.Ordered.class);
    }

    @Test
    void filterOrderShouldBeHighPrecedence() {
        var config = new BodySizeMetricGatewayFilterFactory.Config();
        var filter = factory.apply(config);
        int order = ((org.springframework.core.Ordered) filter).getOrder();
        // Should be near HIGHEST_PRECEDENCE to run early and wrap the chain
        assertThat(order).isLessThan(1000);
    }

    // ─── Config toggles ──────────────────────────────────────────────────────

    @Test
    void includeRequestFalse_producesFilterWithoutError() {
        var config = new BodySizeMetricGatewayFilterFactory.Config();
        config.setIncludeRequest(false);
        var filter = factory.apply(config);
        assertThat(filter).isNotNull();
    }

    @Test
    void includeResponseFalse_producesFilterWithoutError() {
        var config = new BodySizeMetricGatewayFilterFactory.Config();
        config.setIncludeResponse(false);
        var filter = factory.apply(config);
        assertThat(filter).isNotNull();
    }

    @Test
    void bothDisabled_producesFilterWithoutError() {
        var config = new BodySizeMetricGatewayFilterFactory.Config();
        config.setIncludeRequest(false);
        config.setIncludeResponse(false);
        var filter = factory.apply(config);
        assertThat(filter).isNotNull();
    }

    @Test
    void customTagsInConfig_noRegistrationErrorAtApplyTime() {
        var config = new BodySizeMetricGatewayFilterFactory.Config();
        config.setTags(Map.of("service", "payment-api", "tier", "premium"));
        var filter = factory.apply(config);
        assertThat(filter).isNotNull();
    }
}

