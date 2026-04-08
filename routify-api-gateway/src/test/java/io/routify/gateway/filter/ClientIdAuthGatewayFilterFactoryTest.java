package io.routify.gateway.filter;

import io.routify.gateway.auth.properties.ClientProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractNameValueGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ClientIdAuthGatewayFilterFactory}.
 *
 * <p>Covers: matching client ID → organization-id header injected, non-matching → 401,
 * clientIdMapping from config, fallback to ClientProperties, empty mapping.
 */
class ClientIdAuthGatewayFilterFactoryTest {

    private ClientProperties clientProperties;
    private ClientIdAuthGatewayFilterFactory factory;

    @BeforeEach
    void setUp() {
        clientProperties = new ClientProperties();
        factory = new ClientIdAuthGatewayFilterFactory(clientProperties);
    }

    private GatewayFilterChain passThroughChain() {
        return exchange -> Mono.empty();
    }

    private AbstractNameValueGatewayFilterFactory.NameValueConfig nv(String name, String value) {
        var nvc = new AbstractNameValueGatewayFilterFactory.NameValueConfig();
        nvc.setName(name);
        nvc.setValue(value);
        return nvc;
    }

    @Test
    @DisplayName("Matching client ID → organization-id header injected")
    void matchingClientId_injectsOrganizationId() {
        var config = new ClientIdAuthGatewayFilterFactory.Config();
        config.setValues(List.of(nv("X-Client-Id", "client-abc")));
        config.setClientIdMapping(Map.of("org-acme", "client-abc"));
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header("X-Client-Id", "client-abc")
                        .build());

        final String[] capturedOrgId = {null};
        GatewayFilterChain capturingChain = ex -> {
            capturedOrgId[0] = ex.getRequest().getHeaders().getFirst("organization-id");
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, capturingChain))
                .verifyComplete();

        assertThat(capturedOrgId[0]).isEqualTo("org-acme");
    }

    @Test
    @DisplayName("Non-matching client ID → 401 INVALID_CLIENT_ID")
    void nonMatchingClientId_returns401() {
        var config = new ClientIdAuthGatewayFilterFactory.Config();
        config.setValues(List.of(nv("X-Client-Id", "client-abc")));
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header("X-Client-Id", "wrong-client")
                        .build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Missing client ID header → 401")
    void missingHeader_returns401() {
        var config = new ClientIdAuthGatewayFilterFactory.Config();
        config.setValues(List.of(nv("X-Client-Id", "client-abc")));
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test").build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("ClientIdMapping from config takes precedence over static ClientProperties")
    void configMapping_takesPrecedence() {
        // Static properties say orgId is "static-org"
        clientProperties.setClientIdMapping(Map.of("static-org", "client-abc"));

        var config = new ClientIdAuthGatewayFilterFactory.Config();
        config.setValues(List.of(nv("X-Client-Id", "client-abc")));
        config.setClientIdMapping(Map.of("dynamic-org", "client-abc"));
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header("X-Client-Id", "client-abc")
                        .build());

        final String[] capturedOrgId = {null};
        GatewayFilterChain capturingChain = ex -> {
            capturedOrgId[0] = ex.getRequest().getHeaders().getFirst("organization-id");
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, capturingChain))
                .verifyComplete();

        assertThat(capturedOrgId[0]).isEqualTo("dynamic-org");
    }

    @Test
    @DisplayName("Empty config mapping → falls back to ClientProperties")
    void emptyConfigMapping_fallsBackToProperties() {
        clientProperties.setClientIdMapping(Map.of("fallback-org", "client-xyz"));

        var config = new ClientIdAuthGatewayFilterFactory.Config();
        config.setValues(List.of(nv("X-Client-Id", "client-xyz")));
        config.setClientIdMapping(null);
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header("X-Client-Id", "client-xyz")
                        .build());

        final String[] capturedOrgId = {null};
        GatewayFilterChain capturingChain = ex -> {
            capturedOrgId[0] = ex.getRequest().getHeaders().getFirst("organization-id");
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, capturingChain))
                .verifyComplete();

        assertThat(capturedOrgId[0]).isEqualTo("fallback-org");
    }

    @Test
    @DisplayName("Filter order is 0")
    void filterOrder_is0() {
        assertThat(factory.getOrder()).isEqualTo(0);
    }
}

