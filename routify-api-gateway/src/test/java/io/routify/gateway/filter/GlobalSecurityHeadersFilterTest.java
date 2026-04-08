package io.routify.gateway.filter;

import io.routify.gateway.config.GatewayConfigLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GlobalSecurityHeadersFilter}.
 *
 * <p>Covers: global filter enabled → headers applied, disabled via toggle → no headers,
 * order is LOWEST_PRECEDENCE - 1, null config → safe defaults.
 */
class GlobalSecurityHeadersFilterTest {

    private GatewayConfigLoader configLoader;
    private GlobalSecurityHeadersFilter filter;

    @BeforeEach
    void setUp() {
        configLoader = mock(GatewayConfigLoader.class);
        filter = new GlobalSecurityHeadersFilter(configLoader);
    }

    private GatewayFilterChain passThroughChain() {
        return exchange -> Mono.empty();
    }

    @Test
    @DisplayName("Global filter enabled → OWASP headers applied after chain")
    void enabled_appliesHeaders() {
        when(configLoader.getConfig()).thenReturn(Map.of(
                "securityHeaders", Map.of(
                        "enabled", true,
                        "xContentTypeOptions", true,
                        "xFrameOptions", true,
                        "xXssProtection", true
                )
        ));

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test").build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getHeaders().getFirst("X-Content-Type-Options"))
                .isEqualTo("nosniff");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Frame-Options"))
                .isEqualTo("DENY");
    }

    @Test
    @DisplayName("globalFilters.securityHeaders.enabled=false → no headers injected")
    void disabledViaGlobalFilterToggle_noHeaders() {
        Map<String, Object> config = new HashMap<>();
        config.put("securityHeaders", Map.of("enabled", true));
        config.put("globalFilters", Map.of(
                "securityHeaders", Map.of("enabled", false)
        ));
        when(configLoader.getConfig()).thenReturn(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test").build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getHeaders().getFirst("X-Content-Type-Options"))
                .isNull();
    }

    @Test
    @DisplayName("Order is LOWEST_PRECEDENCE - 1")
    void order_isLowestPrecedenceMinus1() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE - 1);
    }

    @Test
    @DisplayName("Null config → safe OWASP defaults applied")
    void nullConfig_appliesDefaults() {
        when(configLoader.getConfig()).thenReturn(null);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test").build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        // Null config means isGlobalFilterEnabled returns true, applySecurityHeaders
        // receives null → falls through to applyDefaults
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Content-Type-Options"))
                .isEqualTo("nosniff");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Frame-Options"))
                .isEqualTo("DENY");
    }

    @Test
    @DisplayName("Empty config map → safe defaults applied (globalFilters section absent)")
    void emptyConfig_appliesDefaults() {
        when(configLoader.getConfig()).thenReturn(Map.of());

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test").build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getHeaders().getFirst("X-Content-Type-Options"))
                .isEqualTo("nosniff");
    }
}

