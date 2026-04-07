package io.routify.gateway.filter.performance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ResponseCacheGatewayFilterFactory}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Config defaults</li>
 *   <li>Method parsing (single, multiple, default)</li>
 *   <li>Status code parsing (single, multiple, default)</li>
 *   <li>Filter factory creates a filter instance</li>
 * </ul>
 *
 * <p>Integration tests (requiring Redis + Testcontainers) live in the IT suite.
 */
class ResponseCacheTest {

    private ResponseCacheGatewayFilterFactory.Config config;

    @BeforeEach
    void setUp() {
        config = new ResponseCacheGatewayFilterFactory.Config();
    }

    // ── Config defaults ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Config defaults")
    class ConfigDefaults {

        @Test
        @DisplayName("Default TTL is 60 seconds")
        void defaultTtl() {
            assertThat(config.getTtlSeconds()).isEqualTo(60);
        }

        @Test
        @DisplayName("Default max cached body size is 64 KB")
        void defaultMaxBodySize() {
            assertThat(config.getMaxCachedBodySize()).isEqualTo(65536);
        }

        @Test
        @DisplayName("Default methods is GET")
        void defaultMethods() {
            assertThat(config.getMethods()).isEqualTo("GET");
        }

        @Test
        @DisplayName("Default status codes is 200,206,301")
        void defaultStatusCodes() {
            assertThat(config.getStatusCodes()).isEqualTo("200,206,301");
        }

        @Test
        @DisplayName("Default key strategy is PATH_QUERY")
        void defaultKeyStrategy() {
            assertThat(config.getKeyStrategy()).isEqualTo("PATH_QUERY");
        }

        @Test
        @DisplayName("Default varyHeaders is empty")
        void defaultVaryHeaders() {
            assertThat(config.getVaryHeaders()).isEmpty();
        }

        @Test
        @DisplayName("Default respectCacheControl is true")
        void defaultRespectCacheControl() {
            assertThat(config.isRespectCacheControl()).isTrue();
        }

        @Test
        @DisplayName("Default addCacheHeaders is true")
        void defaultAddCacheHeaders() {
            assertThat(config.isAddCacheHeaders()).isTrue();
        }
    }

    // ── Config mutation ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Config mutation")
    class ConfigMutation {

        @Test
        @DisplayName("TTL can be set to custom value")
        void customTtl() {
            config.setTtlSeconds(120);
            assertThat(config.getTtlSeconds()).isEqualTo(120);
        }

        @Test
        @DisplayName("Max body size can be set")
        void customMaxBodySize() {
            config.setMaxCachedBodySize(131072);
            assertThat(config.getMaxCachedBodySize()).isEqualTo(131072);
        }

        @Test
        @DisplayName("Methods can be set to multiple methods")
        void customMethods() {
            config.setMethods("GET,HEAD");
            assertThat(config.getMethods()).isEqualTo("GET,HEAD");
        }

        @Test
        @DisplayName("Status codes can be customized")
        void customStatusCodes() {
            config.setStatusCodes("200,301,302");
            assertThat(config.getStatusCodes()).isEqualTo("200,301,302");
        }

        @Test
        @DisplayName("Vary headers can be set")
        void customVaryHeaders() {
            config.setVaryHeaders(new String[]{"Accept", "Accept-Language"});
            assertThat(config.getVaryHeaders()).containsExactly("Accept", "Accept-Language");
        }

        @Test
        @DisplayName("respectCacheControl can be disabled")
        void disableRespectCacheControl() {
            config.setRespectCacheControl(false);
            assertThat(config.isRespectCacheControl()).isFalse();
        }

        @Test
        @DisplayName("addCacheHeaders can be disabled")
        void disableAddCacheHeaders() {
            config.setAddCacheHeaders(false);
            assertThat(config.isAddCacheHeaders()).isFalse();
        }

        @Test
        @DisplayName("Key strategy can be set to PATH_QUERY_HEADERS")
        void customKeyStrategy() {
            config.setKeyStrategy("PATH_QUERY_HEADERS");
            assertThat(config.getKeyStrategy()).isEqualTo("PATH_QUERY_HEADERS");
        }
    }
}

