package io.routify.gateway.filter;

import io.routify.gateway.config.GatewayConfigLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SecurityHeadersGatewayFilterFactory}.
 *
 * <p>Covers: all OWASP headers enabled, master toggle disabled, custom CSP,
 * removeServerHeader, null config → safe defaults, filter order, beforeCommit
 * timing (security headers override downstream filters).
 */
class SecurityHeadersGatewayFilterFactoryTest {

    private GatewayConfigLoader configLoader;
    private SecurityHeadersGatewayFilterFactory factory;

    @BeforeEach
    void setUp() {
        configLoader = mock(GatewayConfigLoader.class);
        factory = new SecurityHeadersGatewayFilterFactory(configLoader);
    }

    /**
     * Pass-through chain that calls {@code setComplete()} to trigger
     * {@code beforeCommit} callbacks registered by the filter under test.
     */
    private GatewayFilterChain passThroughChain() {
        return exchange -> exchange.getResponse().setComplete();
    }

    @Test
    @DisplayName("All OWASP headers enabled → all security headers set")
    void allHeadersEnabled_setsOwaspHeaders() {
        var secHeaders = new HashMap<String, Object>();
        secHeaders.put("enabled", true);
        secHeaders.put("xContentTypeOptions", true);
        secHeaders.put("xFrameOptions", true);
        secHeaders.put("xFrameOptionsValue", "DENY");
        secHeaders.put("xXssProtection", true);
        secHeaders.put("strictTransportSecurity", true);
        secHeaders.put("stsMaxAge", 31536000L);
        secHeaders.put("stsIncludeSubDomains", true);
        secHeaders.put("stsPreload", false);
        secHeaders.put("referrerPolicy", "strict-origin-when-cross-origin");
        secHeaders.put("permissionsPolicy", "geolocation=(), camera=()");
        secHeaders.put("removeServerHeader", true);
        secHeaders.put("removePoweredByHeader", true);
        when(configLoader.getConfig()).thenReturn(Map.of("securityHeaders", secHeaders));

        GatewayFilter filter = factory.apply(new SecurityHeadersGatewayFilterFactory.Config());

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test").build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        var headers = exchange.getResponse().getHeaders();
        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(headers.getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(headers.getFirst("X-XSS-Protection")).isEqualTo("1; mode=block");
        assertThat(headers.getFirst("Strict-Transport-Security"))
                .isEqualTo("max-age=31536000; includeSubDomains");
        assertThat(headers.getFirst("Referrer-Policy"))
                .isEqualTo("strict-origin-when-cross-origin");
        assertThat(headers.getFirst("Permissions-Policy"))
                .isEqualTo("geolocation=(), camera=()");
    }

    @Test
    @DisplayName("enabled=false → no security headers injected")
    void disabled_noHeaders() {
        when(configLoader.getConfig()).thenReturn(Map.of(
                "securityHeaders", Map.of("enabled", false)
        ));

        GatewayFilter filter = factory.apply(new SecurityHeadersGatewayFilterFactory.Config());

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test").build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        var headers = exchange.getResponse().getHeaders();
        assertThat(headers.getFirst("X-Content-Type-Options")).isNull();
        assertThat(headers.getFirst("X-Frame-Options")).isNull();
        assertThat(headers.getFirst("Strict-Transport-Security")).isNull();
    }

    @Test
    @DisplayName("Custom Content-Security-Policy → applied")
    void customCSP_applied() {
        when(configLoader.getConfig()).thenReturn(Map.of(
                "securityHeaders", Map.of(
                        "enabled", true,
                        "contentSecurityPolicy", "default-src 'self'; script-src 'none'"
                )
        ));

        GatewayFilter filter = factory.apply(new SecurityHeadersGatewayFilterFactory.Config());

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test").build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getHeaders().getFirst("Content-Security-Policy"))
                .isEqualTo("default-src 'self'; script-src 'none'");
    }

    @Test
    @DisplayName("Null/missing config section → safe OWASP defaults applied")
    void nullConfigSection_appliesDefaults() {
        when(configLoader.getConfig()).thenReturn(Map.of());

        GatewayFilter filter = factory.apply(new SecurityHeadersGatewayFilterFactory.Config());

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test").build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        var headers = exchange.getResponse().getHeaders();
        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(headers.getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(headers.getFirst("X-XSS-Protection")).isEqualTo("1; mode=block");
        assertThat(headers.getFirst("Strict-Transport-Security"))
                .isEqualTo("max-age=31536000; includeSubDomains");
    }

    @Test
    @DisplayName("Filter order is 100")
    void filterOrder_is100() {
        GatewayFilter filter = factory.apply(new SecurityHeadersGatewayFilterFactory.Config());
        assertThat(filter).isInstanceOf(Ordered.class);
        assertThat(((Ordered) filter).getOrder()).isEqualTo(100);
    }

    @Test
    @DisplayName("Custom headers section → injected")
    void customHeaders_injected() {
        when(configLoader.getConfig()).thenReturn(Map.of(
                "securityHeaders", Map.of(
                        "enabled", true,
                        "customHeaders", Map.of(
                                "X-Custom-Header", "custom-value",
                                "X-Another", "another-value"
                        )
                )
        ));

        GatewayFilter filter = factory.apply(new SecurityHeadersGatewayFilterFactory.Config());

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test").build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getHeaders().getFirst("X-Custom-Header"))
                .isEqualTo("custom-value");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Another"))
                .isEqualTo("another-value");
    }

    @Test
    @DisplayName("Security headers override values set earlier in the chain (beforeCommit)")
    void beforeCommit_overridesDownstreamHeaders() {
        when(configLoader.getConfig()).thenReturn(Map.of(
                "securityHeaders", Map.of(
                        "enabled", true,
                        "xFrameOptions", true,
                        "xFrameOptionsValue", "DENY"
                )
        ));

        GatewayFilter filter = factory.apply(new SecurityHeadersGatewayFilterFactory.Config());

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test").build());

        // Simulate a downstream filter / upstream service setting a conflicting header
        GatewayFilterChain conflictingChain = ex -> {
            ex.getResponse().getHeaders().set("X-Frame-Options", "ALLOW-ALL");
            return ex.getResponse().setComplete();
        };

        StepVerifier.create(filter.filter(exchange, conflictingChain))
                .verifyComplete();

        // Security headers (via beforeCommit) should have the last word
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Frame-Options"))
                .isEqualTo("DENY");
    }
}

