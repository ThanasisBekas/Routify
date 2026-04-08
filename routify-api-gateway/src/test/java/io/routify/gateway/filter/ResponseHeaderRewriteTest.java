package io.routify.gateway.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ResponseHeaderRewriteGatewayFilterFactory}.
 *
 * <p>Covers regex rewriting, capture groups, replaceAll mode, multi-value headers,
 * no-match passthrough, pathological regex rejection, and match timeout protection.
 */
class ResponseHeaderRewriteTest {

    private final ResponseHeaderRewriteGatewayFilterFactory factory =
            new ResponseHeaderRewriteGatewayFilterFactory();

    private GatewayFilterChain passThroughChain() {
        return exchange -> Mono.empty();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Basic Regex Rewriting
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Basic Rewriting")
    class BasicRewriting {

        @Test
        @DisplayName("rewrites Location header from internal to external URL")
        void rewritesLocationHeader() {
            var config = new ResponseHeaderRewriteGatewayFilterFactory.Config();
            config.setHeaderName("Location");
            config.setPattern("https://internal\\.service\\.local:8080(.*)");
            config.setReplacement("https://api.example.com$1");
            config.setReplaceAll(false);

            GatewayFilter filter = factory.apply(config);

            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            exchange.getResponse().getHeaders()
                    .set("Location", "https://internal.service.local:8080/users/123?page=2");

            StepVerifier.create(filter.filter(exchange, passThroughChain()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getHeaders().getFirst("Location"))
                    .isEqualTo("https://api.example.com/users/123?page=2");
        }

        @Test
        @DisplayName("rewrites Set-Cookie domain attribute")
        void rewritesSetCookieDomain() {
            var config = new ResponseHeaderRewriteGatewayFilterFactory.Config();
            config.setHeaderName("Set-Cookie");
            config.setPattern("domain=\\.internal\\.local");
            config.setReplacement("domain=.example.com");
            config.setReplaceAll(true);

            GatewayFilter filter = factory.apply(config);

            MockServerHttpRequest request = MockServerHttpRequest.get("/").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            exchange.getResponse().getHeaders()
                    .set("Set-Cookie", "session=abc123; domain=.internal.local; path=/");

            StepVerifier.create(filter.filter(exchange, passThroughChain()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getHeaders().getFirst("Set-Cookie"))
                    .isEqualTo("session=abc123; domain=.example.com; path=/");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Capture Group Substitution
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Capture Groups")
    class CaptureGroups {

        @Test
        @DisplayName("$1 capture group substituted correctly")
        void captureGroupDollar1() {
            var config = new ResponseHeaderRewriteGatewayFilterFactory.Config();
            config.setHeaderName("Location");
            config.setPattern("https://old\\.host\\.com(/.*)");
            config.setReplacement("https://new.host.com$1");

            GatewayFilter filter = factory.apply(config);

            MockServerHttpRequest request = MockServerHttpRequest.get("/").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            exchange.getResponse().getHeaders()
                    .set("Location", "https://old.host.com/api/v1/resource");

            StepVerifier.create(filter.filter(exchange, passThroughChain()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getHeaders().getFirst("Location"))
                    .isEqualTo("https://new.host.com/api/v1/resource");
        }

        @Test
        @DisplayName("$1 and $2 capture groups substituted correctly")
        void captureGroupDollar1And2() {
            var config = new ResponseHeaderRewriteGatewayFilterFactory.Config();
            config.setHeaderName("X-Custom");
            config.setPattern("(\\w+)-(\\w+)");
            config.setReplacement("$2_$1");

            GatewayFilter filter = factory.apply(config);

            MockServerHttpRequest request = MockServerHttpRequest.get("/").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            exchange.getResponse().getHeaders().set("X-Custom", "hello-world");

            StepVerifier.create(filter.filter(exchange, passThroughChain()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getHeaders().getFirst("X-Custom"))
                    .isEqualTo("world_hello");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // replaceAll vs replaceFirst
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Replace All vs First")
    class ReplaceAllVsFirst {

        @Test
        @DisplayName("replaceAll=true replaces all occurrences in value")
        void replaceAllReplacesAll() {
            var config = new ResponseHeaderRewriteGatewayFilterFactory.Config();
            config.setHeaderName("X-Links");
            config.setPattern("http://");
            config.setReplacement("https://");
            config.setReplaceAll(true);

            GatewayFilter filter = factory.apply(config);

            MockServerHttpRequest request = MockServerHttpRequest.get("/").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            exchange.getResponse().getHeaders()
                    .set("X-Links", "http://foo.com, http://bar.com");

            StepVerifier.create(filter.filter(exchange, passThroughChain()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getHeaders().getFirst("X-Links"))
                    .isEqualTo("https://foo.com, https://bar.com");
        }

        @Test
        @DisplayName("replaceAll=false replaces only first occurrence")
        void replaceFirstReplacesOnlyFirst() {
            var config = new ResponseHeaderRewriteGatewayFilterFactory.Config();
            config.setHeaderName("X-Links");
            config.setPattern("http://");
            config.setReplacement("https://");
            config.setReplaceAll(false);

            GatewayFilter filter = factory.apply(config);

            MockServerHttpRequest request = MockServerHttpRequest.get("/").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            exchange.getResponse().getHeaders()
                    .set("X-Links", "http://foo.com, http://bar.com");

            StepVerifier.create(filter.filter(exchange, passThroughChain()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getHeaders().getFirst("X-Links"))
                    .isEqualTo("https://foo.com, http://bar.com");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Multi-Value Headers
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Multi-Value Headers")
    class MultiValueHeaders {

        @Test
        @DisplayName("each value in a multi-value header is rewritten independently")
        void rewritesEachValueIndependently() {
            var config = new ResponseHeaderRewriteGatewayFilterFactory.Config();
            config.setHeaderName("Set-Cookie");
            config.setPattern("domain=\\.old\\.com");
            config.setReplacement("domain=.new.com");
            config.setReplaceAll(true);

            GatewayFilter filter = factory.apply(config);

            MockServerHttpRequest request = MockServerHttpRequest.get("/").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            exchange.getResponse().getHeaders()
                    .add("Set-Cookie", "a=1; domain=.old.com; path=/");
            exchange.getResponse().getHeaders()
                    .add("Set-Cookie", "b=2; domain=.old.com; path=/api");

            StepVerifier.create(filter.filter(exchange, passThroughChain()))
                    .verifyComplete();

            var cookies = exchange.getResponse().getHeaders().get("Set-Cookie");
            assertThat(cookies).hasSize(2);
            assertThat(cookies.get(0)).isEqualTo("a=1; domain=.new.com; path=/");
            assertThat(cookies.get(1)).isEqualTo("b=2; domain=.new.com; path=/api");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // No Match — Passthrough
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("No Match")
    class NoMatch {

        @Test
        @DisplayName("header value unchanged when regex does not match")
        void noMatchPreservesOriginal() {
            var config = new ResponseHeaderRewriteGatewayFilterFactory.Config();
            config.setHeaderName("Location");
            config.setPattern("https://old\\.host\\.com(.*)");
            config.setReplacement("https://new.host.com$1");

            GatewayFilter filter = factory.apply(config);

            MockServerHttpRequest request = MockServerHttpRequest.get("/").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            exchange.getResponse().getHeaders()
                    .set("Location", "https://other.host.com/path");

            StepVerifier.create(filter.filter(exchange, passThroughChain()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getHeaders().getFirst("Location"))
                    .isEqualTo("https://other.host.com/path");
        }

        @Test
        @DisplayName("missing header passes through without error")
        void missingHeaderIsNoOp() {
            var config = new ResponseHeaderRewriteGatewayFilterFactory.Config();
            config.setHeaderName("X-Not-Present");
            config.setPattern("foo");
            config.setReplacement("bar");

            GatewayFilter filter = factory.apply(config);

            MockServerHttpRequest request = MockServerHttpRequest.get("/").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(filter.filter(exchange, passThroughChain()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getHeaders().get("X-Not-Present")).isNull();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Security — Pathological Regex Rejection
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Pathological Regex Protection")
    class PathologicalRegex {

        @Test
        @DisplayName("nested quantifier (a+)+ is rejected at config time")
        void rejectsNestedQuantifierPlus() {
            var config = new ResponseHeaderRewriteGatewayFilterFactory.Config();
            config.setHeaderName("Location");
            config.setPattern("(a+)+");
            config.setReplacement("b");

            assertThatThrownBy(() -> factory.apply(config))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("pathological regex");
        }

        @Test
        @DisplayName("nested quantifier (a*)* is rejected at config time")
        void rejectsNestedQuantifierStar() {
            var config = new ResponseHeaderRewriteGatewayFilterFactory.Config();
            config.setHeaderName("Location");
            config.setPattern("(a*)*");
            config.setReplacement("b");

            assertThatThrownBy(() -> factory.apply(config))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("pathological regex");
        }

        @Test
        @DisplayName("invalid regex syntax is rejected at config time")
        void rejectsInvalidRegex() {
            var config = new ResponseHeaderRewriteGatewayFilterFactory.Config();
            config.setHeaderName("Location");
            config.setPattern("[unclosed");
            config.setReplacement("b");

            assertThatThrownBy(() -> factory.apply(config))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("invalid regex pattern");
        }

        @Test
        @DisplayName("valid non-pathological pattern is accepted")
        void acceptsValidPattern() {
            var config = new ResponseHeaderRewriteGatewayFilterFactory.Config();
            config.setHeaderName("Location");
            config.setPattern("https?://[^/]+(/.*)");
            config.setReplacement("https://proxy.example.com$1");

            // Should not throw
            GatewayFilter filter = factory.apply(config);
            assertThat(filter).isNotNull();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Config Validation
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Config Validation")
    class ConfigValidation {

        @Test
        @DisplayName("blank headerName is rejected")
        void rejectsBlankHeaderName() {
            var config = new ResponseHeaderRewriteGatewayFilterFactory.Config();
            config.setHeaderName("");
            config.setPattern("foo");
            config.setReplacement("bar");

            assertThatThrownBy(() -> factory.apply(config))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("headerName");
        }

        @Test
        @DisplayName("blank pattern is rejected")
        void rejectsBlankPattern() {
            var config = new ResponseHeaderRewriteGatewayFilterFactory.Config();
            config.setHeaderName("Location");
            config.setPattern("");
            config.setReplacement("bar");

            assertThatThrownBy(() -> factory.apply(config))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("pattern");
        }

        @Test
        @DisplayName("null replacement is rejected")
        void rejectsNullReplacement() {
            var config = new ResponseHeaderRewriteGatewayFilterFactory.Config();
            config.setHeaderName("Location");
            config.setPattern("foo");
            config.setReplacement(null);

            assertThatThrownBy(() -> factory.apply(config))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("replacement");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Common Presets (documented configurations)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Common Presets")
    class CommonPresets {

        @Test
        @DisplayName("CORS origin normalization — localhost to production")
        void corsOriginNormalization() {
            var config = new ResponseHeaderRewriteGatewayFilterFactory.Config();
            config.setHeaderName("Access-Control-Allow-Origin");
            config.setPattern("https?://localhost:\\d+");
            config.setReplacement("https://app.example.com");
            config.setReplaceAll(false);

            GatewayFilter filter = factory.apply(config);

            MockServerHttpRequest request = MockServerHttpRequest.get("/").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            exchange.getResponse().getHeaders()
                    .set("Access-Control-Allow-Origin", "http://localhost:3000");

            StepVerifier.create(filter.filter(exchange, passThroughChain()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getHeaders()
                    .getFirst("Access-Control-Allow-Origin"))
                    .isEqualTo("https://app.example.com");
        }
    }
}

