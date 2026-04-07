package io.routify.gateway.filter.auth;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OAuth2TokenRelayGatewayFilterFactory}.
 *
 * <p>Tests verify:
 * <ul>
 *   <li>Default and custom configuration values</li>
 *   <li>Cache key generation (SHA-256 hash)</li>
 *   <li>Token exchange body building (RFC 8693 format)</li>
 *   <li>Filter factory produces non-null, ordered filter</li>
 *   <li>Fallback mode configuration</li>
 * </ul>
 */
class OAuth2TokenRelayTest {

    private OAuth2TokenRelayGatewayFilterFactory factory;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        factory = new OAuth2TokenRelayGatewayFilterFactory(WebClient.builder(), meterRegistry);
    }

    // ─── Default config values ────────────────────────────────────────────────

    @Test
    void defaultConfigValues() {
        var config = new OAuth2TokenRelayGatewayFilterFactory.Config();
        assertThat(config.getTokenEndpoint()).isNull();
        assertThat(config.getClientId()).isNull();
        assertThat(config.getClientSecret()).isNull();
        assertThat(config.getSubjectTokenType()).isEqualTo("urn:ietf:params:oauth:token-type:access_token");
        assertThat(config.getRequestedTokenType()).isEqualTo("urn:ietf:params:oauth:token-type:access_token");
        assertThat(config.getScope()).isEmpty();
        assertThat(config.getAudience()).isEmpty();
        assertThat(config.getCacheTtlSeconds()).isEqualTo(300);
        assertThat(config.getFallbackMode()).isEqualTo("REJECT");
    }

    @Test
    void customConfigValues() {
        var config = new OAuth2TokenRelayGatewayFilterFactory.Config();
        config.setTokenEndpoint("https://auth.example.com/oauth/token");
        config.setClientId("my-client");
        config.setClientSecret("my-secret");
        config.setSubjectTokenType("urn:ietf:params:oauth:token-type:jwt");
        config.setRequestedTokenType("urn:ietf:params:oauth:token-type:saml2");
        config.setScope("read write");
        config.setAudience("https://api.downstream.com");
        config.setCacheTtlSeconds(600);
        config.setFallbackMode("PASS_THROUGH");

        assertThat(config.getTokenEndpoint()).isEqualTo("https://auth.example.com/oauth/token");
        assertThat(config.getClientId()).isEqualTo("my-client");
        assertThat(config.getClientSecret()).isEqualTo("my-secret");
        assertThat(config.getSubjectTokenType()).isEqualTo("urn:ietf:params:oauth:token-type:jwt");
        assertThat(config.getRequestedTokenType()).isEqualTo("urn:ietf:params:oauth:token-type:saml2");
        assertThat(config.getScope()).isEqualTo("read write");
        assertThat(config.getAudience()).isEqualTo("https://api.downstream.com");
        assertThat(config.getCacheTtlSeconds()).isEqualTo(600);
        assertThat(config.getFallbackMode()).isEqualTo("PASS_THROUGH");
    }

    // ─── Cache key generation ─────────────────────────────────────────────────

    @Nested
    class CacheKeyGeneration {

        @Test
        void cacheKeyShouldBeSha256Hex() {
            String key = OAuth2TokenRelayGatewayFilterFactory.buildCacheKey("token123", "audience1");
            // SHA-256 hex output is 64 chars
            assertThat(key).hasSize(64);
            assertThat(key).matches("[0-9a-f]{64}");
        }

        @Test
        void sameInputsShouldProduceSameKey() {
            String key1 = OAuth2TokenRelayGatewayFilterFactory.buildCacheKey("abc", "aud");
            String key2 = OAuth2TokenRelayGatewayFilterFactory.buildCacheKey("abc", "aud");
            assertThat(key1).isEqualTo(key2);
        }

        @Test
        void differentTokensShouldProduceDifferentKeys() {
            String key1 = OAuth2TokenRelayGatewayFilterFactory.buildCacheKey("token-A", "aud");
            String key2 = OAuth2TokenRelayGatewayFilterFactory.buildCacheKey("token-B", "aud");
            assertThat(key1).isNotEqualTo(key2);
        }

        @Test
        void differentAudiencesShouldProduceDifferentKeys() {
            String key1 = OAuth2TokenRelayGatewayFilterFactory.buildCacheKey("token", "audience-1");
            String key2 = OAuth2TokenRelayGatewayFilterFactory.buildCacheKey("token", "audience-2");
            assertThat(key1).isNotEqualTo(key2);
        }

        @Test
        void nullAudienceShouldNotThrow() {
            String key = OAuth2TokenRelayGatewayFilterFactory.buildCacheKey("token", null);
            assertThat(key).isNotBlank();
            assertThat(key).hasSize(64);
        }
    }

    // ─── Token exchange body ──────────────────────────────────────────────────

    @Nested
    class ExchangeBodyBuilding {

        @Test
        void shouldBuildCorrectExchangeBody() {
            var config = new OAuth2TokenRelayGatewayFilterFactory.Config();
            config.setClientId("my-client");
            config.setClientSecret("my-secret");
            config.setScope("read");
            config.setAudience("https://api.example.com");

            String body = OAuth2TokenRelayGatewayFilterFactory.buildExchangeBody("incoming-token", config);

            assertThat(body).contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Atoken-exchange");
            assertThat(body).contains("subject_token=incoming-token");
            assertThat(body).contains("client_id=my-client");
            assertThat(body).contains("client_secret=my-secret");
            assertThat(body).contains("scope=read");
            assertThat(body).contains("audience=https%3A%2F%2Fapi.example.com");
        }

        @Test
        void shouldOmitEmptyScope() {
            var config = new OAuth2TokenRelayGatewayFilterFactory.Config();
            config.setClientId("cid");
            config.setClientSecret("csecret");
            config.setScope("");
            config.setAudience("");

            String body = OAuth2TokenRelayGatewayFilterFactory.buildExchangeBody("tok", config);
            assertThat(body).doesNotContain("scope=");
            assertThat(body).doesNotContain("audience=");
        }

        @Test
        void shouldOmitNullScope() {
            var config = new OAuth2TokenRelayGatewayFilterFactory.Config();
            config.setClientId("cid");
            config.setClientSecret("csecret");
            config.setScope(null);
            config.setAudience(null);

            String body = OAuth2TokenRelayGatewayFilterFactory.buildExchangeBody("tok", config);
            assertThat(body).doesNotContain("scope=");
            assertThat(body).doesNotContain("audience=");
        }

        @Test
        void shouldIncludeSubjectAndRequestedTokenTypes() {
            var config = new OAuth2TokenRelayGatewayFilterFactory.Config();
            config.setClientId("c");
            config.setClientSecret("s");
            config.setSubjectTokenType("urn:ietf:params:oauth:token-type:jwt");
            config.setRequestedTokenType("urn:ietf:params:oauth:token-type:saml2");

            String body = OAuth2TokenRelayGatewayFilterFactory.buildExchangeBody("tok", config);
            assertThat(body).contains("subject_token_type=urn%3Aietf%3Aparams%3Aoauth%3Atoken-type%3Ajwt");
            assertThat(body).contains("requested_token_type=urn%3Aietf%3Aparams%3Aoauth%3Atoken-type%3Asaml2");
        }

        @Test
        void shouldUrlEncodeSpecialCharactersInToken() {
            var config = new OAuth2TokenRelayGatewayFilterFactory.Config();
            config.setClientId("c");
            config.setClientSecret("s");

            String body = OAuth2TokenRelayGatewayFilterFactory.buildExchangeBody("token with spaces&special=chars", config);
            assertThat(body).contains("subject_token=token+with+spaces%26special%3Dchars");
        }
    }

    // ─── Filter factory ───────────────────────────────────────────────────────

    @Test
    void applyShouldReturnNonNullFilter() {
        var config = new OAuth2TokenRelayGatewayFilterFactory.Config();
        config.setTokenEndpoint("https://auth.example.com/token");
        config.setClientId("cid");
        config.setClientSecret("csecret");
        var filter = factory.apply(config);
        assertThat(filter).isNotNull();
    }

    @Test
    void applyShouldReturnOrderedFilter() {
        var config = new OAuth2TokenRelayGatewayFilterFactory.Config();
        config.setTokenEndpoint("https://auth.example.com/token");
        var filter = factory.apply(config);
        assertThat(filter).isInstanceOf(org.springframework.core.Ordered.class);
    }

    // ─── Metrics registration ─────────────────────────────────────────────────

    @Test
    void applyShouldRegisterMetrics() {
        var config = new OAuth2TokenRelayGatewayFilterFactory.Config();
        config.setTokenEndpoint("https://auth.example.com/token");
        factory.apply(config);

        assertThat(meterRegistry.find("routify.filter.token_relay.exchange_success").counter()).isNotNull();
        assertThat(meterRegistry.find("routify.filter.token_relay.exchange_failure").counter()).isNotNull();
        assertThat(meterRegistry.find("routify.filter.token_relay.cache_hit").counter()).isNotNull();
    }

    // ─── Fallback modes ───────────────────────────────────────────────────────

    @Test
    void fallbackModeDefaultIsReject() {
        var config = new OAuth2TokenRelayGatewayFilterFactory.Config();
        assertThat(config.getFallbackMode()).isEqualTo("REJECT");
    }

    @Test
    void fallbackModeCanBeSetToPassThrough() {
        var config = new OAuth2TokenRelayGatewayFilterFactory.Config();
        config.setFallbackMode("PASS_THROUGH");
        assertThat(config.getFallbackMode()).isEqualTo("PASS_THROUGH");
    }

    @Test
    void fallbackModeCanBeSetToStrip() {
        var config = new OAuth2TokenRelayGatewayFilterFactory.Config();
        config.setFallbackMode("STRIP");
        assertThat(config.getFallbackMode()).isEqualTo("STRIP");
    }
}

