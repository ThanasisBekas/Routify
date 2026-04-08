package io.routify.gateway.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ApiVersioningGatewayFilterFactory}.
 *
 * <p>Covers: HEADER, QUERY, PATH strategies, blank version passthrough,
 * PATH idempotency, unknown strategy, config defaults.
 */
class ApiVersioningGatewayFilterFactoryTest {

    private final ApiVersioningGatewayFilterFactory factory = new ApiVersioningGatewayFilterFactory();

    private GatewayFilterChain passThroughChain() {
        return exchange -> Mono.empty();
    }

    // ─── HEADER Strategy ──────────────────────────────────────────────────────

    @Test
    @DisplayName("HEADER strategy → injects X-Api-Version header")
    void headerStrategy_injectsVersionHeader() {
        var config = new ApiVersioningGatewayFilterFactory.Config();
        config.setVersion("v2");
        config.setStrategy("HEADER");
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/users").build());

        final String[] capturedVersion = {null};
        GatewayFilterChain capturingChain = ex -> {
            capturedVersion[0] = ex.getRequest().getHeaders().getFirst("X-Api-Version");
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, capturingChain))
                .verifyComplete();

        assertThat(capturedVersion[0]).isEqualTo("v2");
    }

    @Test
    @DisplayName("HEADER strategy with custom header name")
    void headerStrategy_customHeaderName() {
        var config = new ApiVersioningGatewayFilterFactory.Config();
        config.setVersion("3");
        config.setStrategy("HEADER");
        config.setVersionHeader("Api-Version");
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/users").build());

        final String[] capturedVersion = {null};
        GatewayFilterChain capturingChain = ex -> {
            capturedVersion[0] = ex.getRequest().getHeaders().getFirst("Api-Version");
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, capturingChain))
                .verifyComplete();

        assertThat(capturedVersion[0]).isEqualTo("3");
    }

    // ─── QUERY Strategy ───────────────────────────────────────────────────────

    @Test
    @DisplayName("QUERY strategy → appends version query parameter")
    void queryStrategy_appendsQueryParam() {
        var config = new ApiVersioningGatewayFilterFactory.Config();
        config.setVersion("v2");
        config.setStrategy("QUERY");
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/users").build());

        final String[] capturedUri = {null};
        GatewayFilterChain capturingChain = ex -> {
            capturedUri[0] = ex.getRequest().getURI().toString();
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, capturingChain))
                .verifyComplete();

        assertThat(capturedUri[0]).contains("version=v2");
    }

    // ─── PATH Strategy ────────────────────────────────────────────────────────

    @Test
    @DisplayName("PATH strategy → prepends version prefix to path")
    void pathStrategy_prependsVersionPrefix() {
        var config = new ApiVersioningGatewayFilterFactory.Config();
        config.setVersion("v2");
        config.setStrategy("PATH");
        config.setVersionPrefix("/v2");
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/users").build());

        final String[] capturedPath = {null};
        GatewayFilterChain capturingChain = ex -> {
            capturedPath[0] = ex.getRequest().getURI().getRawPath();
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, capturingChain))
                .verifyComplete();

        assertThat(capturedPath[0]).isEqualTo("/v2/api/users");
    }

    @Test
    @DisplayName("PATH strategy — path already prefixed → not added again (idempotent)")
    void pathStrategy_alreadyPrefixed_skips() {
        var config = new ApiVersioningGatewayFilterFactory.Config();
        config.setVersion("v2");
        config.setStrategy("PATH");
        config.setVersionPrefix("/v2");
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v2/api/users").build());

        final String[] capturedPath = {null};
        GatewayFilterChain capturingChain = ex -> {
            capturedPath[0] = ex.getRequest().getURI().getRawPath();
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, capturingChain))
                .verifyComplete();

        assertThat(capturedPath[0]).isEqualTo("/v2/api/users");
    }

    // ─── Blank Version ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Blank version → passthrough (no modification)")
    void blankVersion_passesThrough() {
        var config = new ApiVersioningGatewayFilterFactory.Config();
        config.setVersion("");
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/users").build());

        final String[] capturedPath = {null};
        GatewayFilterChain capturingChain = ex -> {
            capturedPath[0] = ex.getRequest().getURI().getRawPath();
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, capturingChain))
                .verifyComplete();

        assertThat(capturedPath[0]).isEqualTo("/api/users");
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    // ─── Unknown Strategy ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Unknown strategy → passthrough unchanged")
    void unknownStrategy_passesThrough() {
        var config = new ApiVersioningGatewayFilterFactory.Config();
        config.setVersion("v3");
        config.setStrategy("COOKIE");
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/users").build());

        final String[] capturedPath = {null};
        GatewayFilterChain capturingChain = ex -> {
            capturedPath[0] = ex.getRequest().getURI().getRawPath();
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, capturingChain))
                .verifyComplete();

        assertThat(capturedPath[0]).isEqualTo("/api/users");
    }

    // ─── Config Defaults ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Default config: strategy=HEADER, versionHeader=X-Api-Version")
    void configDefaults() {
        var config = new ApiVersioningGatewayFilterFactory.Config();
        assertThat(config.getStrategy()).isEqualTo("HEADER");
        assertThat(config.getVersionHeader()).isEqualTo("X-Api-Version");
        assertThat(config.getVersionParam()).isEqualTo("version");
        assertThat(config.getVersion()).isNull();
        assertThat(config.getVersionPrefix()).isNull();
    }
}

