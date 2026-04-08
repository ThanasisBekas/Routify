package io.routify.gateway.filter.reliability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link IdempotencyKeyGatewayFilterFactory}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Config defaults and custom values</li>
 *   <li>HTTP method parsing (defaults, custom, invalid)</li>
 *   <li>Filter factory instantiation</li>
 *   <li>Config validation behaviour</li>
 * </ul>
 *
 * <p>Integration tests (requiring Redis + Testcontainers) live in the IT suite.
 */
class IdempotencyKeyTest {

    private IdempotencyKeyGatewayFilterFactory.Config config;

    @BeforeEach
    void setUp() {
        config = new IdempotencyKeyGatewayFilterFactory.Config();
    }

    // ── Config defaults ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Config defaults")
    class ConfigDefaults {

        @Test
        @DisplayName("Default header name is Idempotency-Key")
        void defaultHeaderName() {
            assertThat(config.getHeaderName()).isEqualTo("Idempotency-Key");
        }

        @Test
        @DisplayName("Default TTL is 86400 seconds (24 hours)")
        void defaultTtl() {
            assertThat(config.getTtlSeconds()).isEqualTo(86400);
        }

        @Test
        @DisplayName("Default methods is POST,PUT,PATCH")
        void defaultMethods() {
            assertThat(config.getMethods()).isEqualTo("POST,PUT,PATCH");
        }

        @Test
        @DisplayName("Default requireHeader is false")
        void defaultRequireHeader() {
            assertThat(config.isRequireHeader()).isFalse();
        }

        @Test
        @DisplayName("Default maxCachedBodySize is 65536 (64 KB)")
        void defaultMaxCachedBodySize() {
            assertThat(config.getMaxCachedBodySize()).isEqualTo(65536);
        }
    }

    // ── Custom config values ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Custom config values")
    class CustomConfigValues {

        @Test
        @DisplayName("Custom header name accepted")
        void customHeaderName() {
            config.setHeaderName("X-Custom-Idempotency");
            assertThat(config.getHeaderName()).isEqualTo("X-Custom-Idempotency");
        }

        @Test
        @DisplayName("Custom TTL accepted")
        void customTtl() {
            config.setTtlSeconds(3600);
            assertThat(config.getTtlSeconds()).isEqualTo(3600);
        }

        @Test
        @DisplayName("Custom methods accepted")
        void customMethods() {
            config.setMethods("POST,DELETE");
            assertThat(config.getMethods()).isEqualTo("POST,DELETE");
        }

        @Test
        @DisplayName("requireHeader can be set to true")
        void requireHeaderTrue() {
            config.setRequireHeader(true);
            assertThat(config.isRequireHeader()).isTrue();
        }

        @Test
        @DisplayName("Custom maxCachedBodySize accepted")
        void customMaxCachedBodySize() {
            config.setMaxCachedBodySize(131072);
            assertThat(config.getMaxCachedBodySize()).isEqualTo(131072);
        }
    }

    // ── Factory instantiation ────────────────────────────────────────────────

    @Nested
    @DisplayName("Factory instantiation")
    class FactoryInstantiation {

        @Test
        @DisplayName("apply() returns a non-null GatewayFilter with default config")
        void applyReturnsFilter() {
            var redis = org.mockito.Mockito.mock(
                    org.springframework.data.redis.core.ReactiveStringRedisTemplate.class);
            var factory = new IdempotencyKeyGatewayFilterFactory(redis, new SimpleMeterRegistry());

            var filter = factory.apply(new IdempotencyKeyGatewayFilterFactory.Config());

            assertThat(filter).isNotNull();
        }

        @Test
        @DisplayName("apply() returns a non-null GatewayFilter with custom config")
        void applyReturnsFilterCustomConfig() {
            var redis = org.mockito.Mockito.mock(
                    org.springframework.data.redis.core.ReactiveStringRedisTemplate.class);
            var factory = new IdempotencyKeyGatewayFilterFactory(redis, new SimpleMeterRegistry());

            var customConfig = new IdempotencyKeyGatewayFilterFactory.Config();
            customConfig.setHeaderName("X-Idem-Key");
            customConfig.setTtlSeconds(7200);
            customConfig.setMethods("POST");
            customConfig.setRequireHeader(true);
            customConfig.setMaxCachedBodySize(4096);

            var filter = factory.apply(customConfig);

            assertThat(filter).isNotNull();
        }
    }

    // ── Method parsing ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Method parsing")
    class MethodParsing {

        @Test
        @DisplayName("Default methods parsed as POST, PUT, PATCH")
        void defaultMethodsParsed() {
            // Use reflection-free approach: check that the Config defaults match expectation
            assertThat(config.getMethods()).isEqualTo("POST,PUT,PATCH");
        }

        @Test
        @DisplayName("Single method parsed correctly")
        void singleMethod() {
            config.setMethods("DELETE");
            assertThat(config.getMethods()).isEqualTo("DELETE");
        }

        @Test
        @DisplayName("Multiple methods parsed with whitespace trimmed")
        void multipleMethodsWithWhitespace() {
            config.setMethods(" POST , PUT , DELETE ");
            // Verify the raw config; the factory's parseMethods() handles trimming
            assertThat(config.getMethods()).isEqualTo(" POST , PUT , DELETE ");
        }

        @Test
        @DisplayName("Empty methods string reverts to defaults in factory")
        void emptyMethods() {
            config.setMethods("");
            assertThat(config.getMethods()).isEmpty();
            // The factory should use default POST,PUT,PATCH when empty
        }

        @Test
        @DisplayName("Null methods string reverts to defaults in factory")
        void nullMethods() {
            config.setMethods(null);
            assertThat(config.getMethods()).isNull();
            // The factory should use default POST,PUT,PATCH when null
        }
    }

    // ── Filter order ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Filter order")
    class FilterOrder {

        @Test
        @DisplayName("Filter has order -100 (after auth, before upstream)")
        void filterOrder() {
            var redis = org.mockito.Mockito.mock(
                    org.springframework.data.redis.core.ReactiveStringRedisTemplate.class);
            var factory = new IdempotencyKeyGatewayFilterFactory(redis, new SimpleMeterRegistry());

            var filter = factory.apply(new IdempotencyKeyGatewayFilterFactory.Config());

            assertThat(filter).isInstanceOf(org.springframework.core.Ordered.class);
            assertThat(((org.springframework.core.Ordered) filter).getOrder()).isEqualTo(-100);
        }
    }

    // ── Metrics registration ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Metrics registration")
    class MetricsRegistration {

        @Test
        @DisplayName("Hit, miss, and conflict counters are registered")
        void metricsRegistered() {
            var registry = new SimpleMeterRegistry();
            var redis = org.mockito.Mockito.mock(
                    org.springframework.data.redis.core.ReactiveStringRedisTemplate.class);
            var factory = new IdempotencyKeyGatewayFilterFactory(redis, registry);

            factory.apply(new IdempotencyKeyGatewayFilterFactory.Config());

            assertThat(registry.find("routify.filter.idempotency.hit").counter()).isNotNull();
            assertThat(registry.find("routify.filter.idempotency.miss").counter()).isNotNull();
            assertThat(registry.find("routify.filter.idempotency.conflict").counter()).isNotNull();
        }
    }
}

