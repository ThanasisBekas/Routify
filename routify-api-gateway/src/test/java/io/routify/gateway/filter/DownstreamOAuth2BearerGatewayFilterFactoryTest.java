package io.routify.gateway.filter;

import io.routify.gateway.downstream.oauth2.Oauth2AccessTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DownstreamOAuth2BearerGatewayFilterFactory}.
 *
 * <p>Covers: valid provider → Bearer injected, missing provider name → 401,
 * forwardCallerAuth mode, factory order.
 */
class DownstreamOAuth2BearerGatewayFilterFactoryTest {

    private Oauth2AccessTokenProvider tokenProvider;
    private DownstreamOAuth2BearerGatewayFilterFactory factory;

    @BeforeEach
    void setUp() {
        tokenProvider = mock(Oauth2AccessTokenProvider.class);
        factory = new DownstreamOAuth2BearerGatewayFilterFactory(tokenProvider);
    }

    private GatewayFilterChain passThroughChain() {
        return exchange -> Mono.empty();
    }

    @Test
    @DisplayName("Valid provider → Authorization: Bearer <token> injected")
    void validProvider_injectsBearerToken() {
        var config = new DownstreamOAuth2BearerGatewayFilterFactory.Config();
        config.setOauth2ProviderName("my-provider");
        config.setForwardCallerAuth(false);
        GatewayFilter filter = factory.apply(config);

        when(tokenProvider.accessTokenClientCredentials("my-provider"))
                .thenReturn(Mono.just("eyJhbGciOi-mock-token"));

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/downstream").build());

        final String[] capturedAuth = {null};
        GatewayFilterChain capturingChain = ex -> {
            capturedAuth[0] = ex.getRequest().getHeaders().getFirst("Authorization");
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, capturingChain))
                .verifyComplete();

        assertThat(capturedAuth[0]).isEqualTo("Bearer eyJhbGciOi-mock-token");
    }

    @Test
    @DisplayName("Missing provider name → 401 MISSING_OAUTH2_PROVIDER")
    void missingProviderName_returns401() {
        var config = new DownstreamOAuth2BearerGatewayFilterFactory.Config();
        config.setOauth2ProviderName(null);
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/downstream").build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Empty provider name → 401 MISSING_OAUTH2_PROVIDER")
    void emptyProviderName_returns401() {
        var config = new DownstreamOAuth2BearerGatewayFilterFactory.Config();
        config.setOauth2ProviderName("");
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/downstream").build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("forwardCallerAuth=true → uses accessTokenForwardedAuth")
    void forwardCallerAuth_usesForwardedAuth() {
        var config = new DownstreamOAuth2BearerGatewayFilterFactory.Config();
        config.setOauth2ProviderName("forwarding-provider");
        config.setForwardCallerAuth(true);
        GatewayFilter filter = factory.apply(config);

        when(tokenProvider.accessTokenForwardedAuth(anyString(), any()))
                .thenReturn(Mono.just("forwarded-token-xyz"));

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/downstream")
                        .header("Authorization", "Bearer caller-jwt")
                        .build());

        final String[] capturedAuth = {null};
        GatewayFilterChain capturingChain = ex -> {
            capturedAuth[0] = ex.getRequest().getHeaders().getFirst("Authorization");
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, capturingChain))
                .verifyComplete();

        assertThat(capturedAuth[0]).isEqualTo("Bearer forwarded-token-xyz");
    }

    @Test
    @DisplayName("Factory order is 1")
    void factoryOrder_is1() {
        assertThat(factory.getOrder()).isEqualTo(1);
    }
}

