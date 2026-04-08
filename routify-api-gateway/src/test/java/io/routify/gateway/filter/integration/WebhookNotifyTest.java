package io.routify.gateway.filter.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WebhookNotifyGatewayFilterFactory}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Config defaults and custom values</li>
 *   <li>Trigger condition matching ({@code 5xx}, {@code 4xx}, {@code ALL}, specific codes)</li>
 *   <li>Header match condition</li>
 *   <li>HMAC-SHA256 signing</li>
 *   <li>Cooldown tracking</li>
 *   <li>Filter factory instantiation</li>
 * </ul>
 *
 * <p>WebClient dispatch is tested via integration tests (requires a running HTTP server).
 */
class WebhookNotifyTest {

    private WebhookNotifyGatewayFilterFactory.Config config;

    @BeforeEach
    void setUp() {
        config = new WebhookNotifyGatewayFilterFactory.Config();
    }

    // ── Config defaults ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Config defaults")
    class ConfigDefaults {

        @Test
        @DisplayName("Default webhookUrl is empty string")
        void defaultWebhookUrl() {
            assertThat(config.getWebhookUrl()).isEmpty();
        }

        @Test
        @DisplayName("Default secret is empty string")
        void defaultSecret() {
            assertThat(config.getSecret()).isEmpty();
        }

        @Test
        @DisplayName("Default triggerOn is 5xx")
        void defaultTriggerOn() {
            assertThat(config.getTriggerOn()).isEqualTo("5xx");
        }

        @Test
        @DisplayName("Default headerMatch is empty string")
        void defaultHeaderMatch() {
            assertThat(config.getHeaderMatch()).isEmpty();
        }

        @Test
        @DisplayName("Default includeRequestHeaders is false")
        void defaultIncludeRequestHeaders() {
            assertThat(config.isIncludeRequestHeaders()).isFalse();
        }

        @Test
        @DisplayName("Default includeResponseStatus is true")
        void defaultIncludeResponseStatus() {
            assertThat(config.isIncludeResponseStatus()).isTrue();
        }

        @Test
        @DisplayName("Default maxPayloadSize is 4096")
        void defaultMaxPayloadSize() {
            assertThat(config.getMaxPayloadSize()).isEqualTo(4096);
        }

        @Test
        @DisplayName("Default cooldownSeconds is 10")
        void defaultCooldownSeconds() {
            assertThat(config.getCooldownSeconds()).isEqualTo(10);
        }
    }

    // ── Config mutation ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Config mutation")
    class ConfigMutation {

        @Test
        @DisplayName("webhookUrl can be set")
        void customWebhookUrl() {
            config.setWebhookUrl("https://hooks.example.com/alert");
            assertThat(config.getWebhookUrl()).isEqualTo("https://hooks.example.com/alert");
        }

        @Test
        @DisplayName("triggerOn can be set to ALL")
        void customTriggerOn() {
            config.setTriggerOn("ALL");
            assertThat(config.getTriggerOn()).isEqualTo("ALL");
        }

        @Test
        @DisplayName("cooldownSeconds can be set")
        void customCooldown() {
            config.setCooldownSeconds(30);
            assertThat(config.getCooldownSeconds()).isEqualTo(30);
        }

        @Test
        @DisplayName("headerMatch can be set")
        void customHeaderMatch() {
            config.setHeaderMatch("X-Flag=true");
            assertThat(config.getHeaderMatch()).isEqualTo("X-Flag=true");
        }
    }

    // ── Trigger condition matching ───────────────────────────────────────────

    @Nested
    @DisplayName("Trigger condition matching")
    class TriggerMatching {

        @Test
        @DisplayName("5xx matches status 500")
        void matches5xx_500() {
            assertThat(WebhookNotifyGatewayFilterFactory.matchesTrigger("5xx", 500)).isTrue();
        }

        @Test
        @DisplayName("5xx matches status 503")
        void matches5xx_503() {
            assertThat(WebhookNotifyGatewayFilterFactory.matchesTrigger("5xx", 503)).isTrue();
        }

        @Test
        @DisplayName("5xx matches status 599")
        void matches5xx_599() {
            assertThat(WebhookNotifyGatewayFilterFactory.matchesTrigger("5xx", 599)).isTrue();
        }

        @Test
        @DisplayName("5xx does NOT match status 200")
        void noMatch5xx_200() {
            assertThat(WebhookNotifyGatewayFilterFactory.matchesTrigger("5xx", 200)).isFalse();
        }

        @Test
        @DisplayName("5xx does NOT match status 404")
        void noMatch5xx_404() {
            assertThat(WebhookNotifyGatewayFilterFactory.matchesTrigger("5xx", 404)).isFalse();
        }

        @Test
        @DisplayName("4xx matches status 400")
        void matches4xx_400() {
            assertThat(WebhookNotifyGatewayFilterFactory.matchesTrigger("4xx", 400)).isTrue();
        }

        @Test
        @DisplayName("4xx matches status 404")
        void matches4xx_404() {
            assertThat(WebhookNotifyGatewayFilterFactory.matchesTrigger("4xx", 404)).isTrue();
        }

        @Test
        @DisplayName("4xx matches status 499")
        void matches4xx_499() {
            assertThat(WebhookNotifyGatewayFilterFactory.matchesTrigger("4xx", 499)).isTrue();
        }

        @Test
        @DisplayName("4xx does NOT match status 200")
        void noMatch4xx_200() {
            assertThat(WebhookNotifyGatewayFilterFactory.matchesTrigger("4xx", 200)).isFalse();
        }

        @Test
        @DisplayName("4xx does NOT match status 500")
        void noMatch4xx_500() {
            assertThat(WebhookNotifyGatewayFilterFactory.matchesTrigger("4xx", 500)).isFalse();
        }

        @Test
        @DisplayName("ALL matches any status code")
        void matchesAll_200() {
            assertThat(WebhookNotifyGatewayFilterFactory.matchesTrigger("ALL", 200)).isTrue();
        }

        @Test
        @DisplayName("ALL matches 500")
        void matchesAll_500() {
            assertThat(WebhookNotifyGatewayFilterFactory.matchesTrigger("ALL", 500)).isTrue();
        }

        @Test
        @DisplayName("ALL matches 301")
        void matchesAll_301() {
            assertThat(WebhookNotifyGatewayFilterFactory.matchesTrigger("ALL", 301)).isTrue();
        }

        @Test
        @DisplayName("Specific codes: 503,504 matches 503")
        void matchesSpecific_503() {
            assertThat(WebhookNotifyGatewayFilterFactory.matchesTrigger("503,504", 503)).isTrue();
        }

        @Test
        @DisplayName("Specific codes: 503,504 matches 504")
        void matchesSpecific_504() {
            assertThat(WebhookNotifyGatewayFilterFactory.matchesTrigger("503,504", 504)).isTrue();
        }

        @Test
        @DisplayName("Specific codes: 503,504 does NOT match 500")
        void noMatchSpecific_500() {
            assertThat(WebhookNotifyGatewayFilterFactory.matchesTrigger("503,504", 500)).isFalse();
        }

        @Test
        @DisplayName("Specific codes with spaces: '503 , 504' matches 504")
        void matchesSpecificWithSpaces() {
            assertThat(WebhookNotifyGatewayFilterFactory.matchesTrigger("503 , 504", 504)).isTrue();
        }

        @Test
        @DisplayName("Null triggerOn defaults to 5xx")
        void nullDefaultsTo5xx() {
            assertThat(WebhookNotifyGatewayFilterFactory.matchesTrigger(null, 503)).isTrue();
            assertThat(WebhookNotifyGatewayFilterFactory.matchesTrigger(null, 200)).isFalse();
        }

        @Test
        @DisplayName("Empty triggerOn defaults to 5xx")
        void emptyDefaultsTo5xx() {
            assertThat(WebhookNotifyGatewayFilterFactory.matchesTrigger("", 503)).isTrue();
            assertThat(WebhookNotifyGatewayFilterFactory.matchesTrigger("", 200)).isFalse();
        }
    }

    // ── Header match condition ────────────────────────────────────────────────

    @Nested
    @DisplayName("Header match condition")
    class HeaderMatching {

        @Test
        @DisplayName("Matches when request header has expected value")
        void matchesRequestHeader() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/")
                    .header("X-Flag", "true")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            assertThat(WebhookNotifyGatewayFilterFactory.matchesHeader("X-Flag=true", exchange)).isTrue();
        }

        @Test
        @DisplayName("Does not match when header value differs")
        void noMatchDifferentValue() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/")
                    .header("X-Flag", "false")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            assertThat(WebhookNotifyGatewayFilterFactory.matchesHeader("X-Flag=true", exchange)).isFalse();
        }

        @Test
        @DisplayName("Does not match when header is absent")
        void noMatchAbsentHeader() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            assertThat(WebhookNotifyGatewayFilterFactory.matchesHeader("X-Flag=true", exchange)).isFalse();
        }

        @Test
        @DisplayName("Invalid headerMatch format (no =) returns false")
        void invalidFormatNoEquals() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            assertThat(WebhookNotifyGatewayFilterFactory.matchesHeader("X-Flag", exchange)).isFalse();
        }

        @Test
        @DisplayName("Empty headerMatch returns false")
        void emptyHeaderMatch() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            assertThat(WebhookNotifyGatewayFilterFactory.matchesHeader("", exchange)).isFalse();
        }

        @Test
        @DisplayName("Matches response header when request header absent")
        void matchesResponseHeader() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            exchange.getResponse().getHeaders().set("X-Flag", "true");

            assertThat(WebhookNotifyGatewayFilterFactory.matchesHeader("X-Flag=true", exchange)).isTrue();
        }
    }

    // ── HMAC signing ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("HMAC-SHA256 signing")
    class HmacSigning {

        @Test
        @DisplayName("Computes deterministic HMAC for known input")
        void computesDeterministicHmac() {
            String hmac1 = WebhookNotifyGatewayFilterFactory.computeHmacSha256(
                    "{\"test\":true}", "my-secret");
            String hmac2 = WebhookNotifyGatewayFilterFactory.computeHmacSha256(
                    "{\"test\":true}", "my-secret");

            assertThat(hmac1).isNotNull();
            assertThat(hmac1).isNotEmpty();
            assertThat(hmac1).isEqualTo(hmac2);
        }

        @Test
        @DisplayName("Different payloads produce different HMACs")
        void differentPayloadsDifferentHmacs() {
            String hmac1 = WebhookNotifyGatewayFilterFactory.computeHmacSha256(
                    "{\"a\":1}", "secret");
            String hmac2 = WebhookNotifyGatewayFilterFactory.computeHmacSha256(
                    "{\"b\":2}", "secret");

            assertThat(hmac1).isNotEqualTo(hmac2);
        }

        @Test
        @DisplayName("Different secrets produce different HMACs")
        void differentSecretsDifferentHmacs() {
            String hmac1 = WebhookNotifyGatewayFilterFactory.computeHmacSha256(
                    "{\"test\":true}", "secret-1");
            String hmac2 = WebhookNotifyGatewayFilterFactory.computeHmacSha256(
                    "{\"test\":true}", "secret-2");

            assertThat(hmac1).isNotEqualTo(hmac2);
        }

        @Test
        @DisplayName("HMAC is a hex string")
        void hmacIsHexString() {
            String hmac = WebhookNotifyGatewayFilterFactory.computeHmacSha256(
                    "payload", "secret");

            assertThat(hmac).isNotNull();
            assertThat(hmac).matches("^[0-9a-f]+$");
        }

        @Test
        @DisplayName("HMAC length is 64 hex chars (SHA-256 = 32 bytes)")
        void hmacLength() {
            String hmac = WebhookNotifyGatewayFilterFactory.computeHmacSha256(
                    "payload", "secret");

            assertThat(hmac).hasSize(64);
        }
    }

    // ── Factory instantiation ────────────────────────────────────────────────

    @Nested
    @DisplayName("Factory instantiation")
    class FactoryInstantiation {

        @Test
        @DisplayName("Factory creates a non-null filter")
        void createsFilter() {
            var factory = new WebhookNotifyGatewayFilterFactory(
                    org.springframework.web.reactive.function.client.WebClient.builder());
            config.setWebhookUrl("https://hooks.example.com/test");
            var filter = factory.apply(config);
            assertThat(filter).isNotNull();
        }

        @Test
        @DisplayName("Factory creates filter with full config")
        void createsFilterWithFullConfig() {
            var factory = new WebhookNotifyGatewayFilterFactory(
                    org.springframework.web.reactive.function.client.WebClient.builder());
            config.setWebhookUrl("https://hooks.example.com/test");
            config.setSecret("my-secret");
            config.setTriggerOn("503,504");
            config.setHeaderMatch("X-Flag=true");
            config.setIncludeRequestHeaders(true);
            config.setCooldownSeconds(30);
            config.setMaxPayloadSize(8192);

            var filter = factory.apply(config);
            assertThat(filter).isNotNull();
        }
    }
}

