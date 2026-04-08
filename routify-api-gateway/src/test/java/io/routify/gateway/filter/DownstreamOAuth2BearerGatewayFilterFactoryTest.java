package io.routify.gateway.filter;

import io.routify.gateway.downstream.oauth2.Oauth2AccessTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DownstreamOAuth2BearerGatewayFilterFactory}.
 *
 * <p>Covers: named provider path (legacy), direct OAuth2 config path (P-25),
 * forwardCallerAuth mode, factory order, and Config#hasDirectOAuth2Config().
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

    // ── Named provider path (legacy) ──────────────────────────────────────

    @Nested
    @DisplayName("Named provider path (legacy)")
    class NamedProviderTests {

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
    }

    // ── Direct OAuth2 config path (P-25) ──────────────────────────────────

    @Nested
    @DisplayName("Direct OAuth2 config path (P-25)")
    class DirectConfigTests {

        @Test
        @DisplayName("Direct config → uses accessTokenDirectClientCredentials")
        void directConfig_usesDirectCc() {
            var config = new DownstreamOAuth2BearerGatewayFilterFactory.Config();
            config.setTokenUri("https://auth.example.com/oauth/token");
            config.setClientId("my-client");
            config.setClientSecret("my-secret");
            config.setScope("read write");
            GatewayFilter filter = factory.apply(config);

            when(tokenProvider.accessTokenDirectClientCredentials(
                    eq("https://auth.example.com/oauth/token"),
                    eq("my-client"), eq("my-secret"), eq("read write"), eq(false)))
                    .thenReturn(Mono.just("direct-cc-token-abc"));

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/downstream").build());

            final String[] capturedAuth = {null};
            GatewayFilterChain capturingChain = ex -> {
                capturedAuth[0] = ex.getRequest().getHeaders().getFirst("Authorization");
                return Mono.empty();
            };

            StepVerifier.create(filter.filter(exchange, capturingChain))
                    .verifyComplete();

            assertThat(capturedAuth[0]).isEqualTo("Bearer direct-cc-token-abc");
        }

        @Test
        @DisplayName("Direct config with forwardCallerAuth → uses accessTokenDirectForwardedAuth")
        void directConfig_forwardCallerAuth() {
            var config = new DownstreamOAuth2BearerGatewayFilterFactory.Config();
            config.setTokenUri("https://auth.example.com/oauth/token");
            config.setClientId("my-client");
            config.setClientSecret("my-secret");
            config.setForwardCallerAuth(true);
            GatewayFilter filter = factory.apply(config);

            when(tokenProvider.accessTokenDirectForwardedAuth(
                    eq("https://auth.example.com/oauth/token"), any()))
                    .thenReturn(Mono.just("direct-fwd-token-xyz"));

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

            assertThat(capturedAuth[0]).isEqualTo("Bearer direct-fwd-token-xyz");
        }

        @Test
        @DisplayName("Direct config takes priority over oauth2ProviderName")
        void directConfig_priorityOverNamed() {
            var config = new DownstreamOAuth2BearerGatewayFilterFactory.Config();
            config.setOauth2ProviderName("should-not-be-used");
            config.setTokenUri("https://auth.example.com/oauth/token");
            config.setClientId("direct-client");
            config.setClientSecret("direct-secret");
            GatewayFilter filter = factory.apply(config);

            when(tokenProvider.accessTokenDirectClientCredentials(
                    anyString(), anyString(), anyString(), any(), anyBoolean()))
                    .thenReturn(Mono.just("direct-wins-token"));

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/downstream").build());

            final String[] capturedAuth = {null};
            GatewayFilterChain capturingChain = ex -> {
                capturedAuth[0] = ex.getRequest().getHeaders().getFirst("Authorization");
                return Mono.empty();
            };

            StepVerifier.create(filter.filter(exchange, capturingChain))
                    .verifyComplete();

            assertThat(capturedAuth[0]).isEqualTo("Bearer direct-wins-token");
        }
    }

    // ── Config#hasDirectOAuth2Config() ────────────────────────────────────

    @Nested
    @DisplayName("Config#hasDirectOAuth2Config()")
    class HasDirectConfigTests {

        @Test
        @DisplayName("All three fields set → true")
        void allFieldsSet_returnsTrue() {
            var config = new DownstreamOAuth2BearerGatewayFilterFactory.Config();
            config.setTokenUri("https://auth.example.com/token");
            config.setClientId("client");
            config.setClientSecret("secret");
            assertThat(config.hasDirectOAuth2Config()).isTrue();
        }

        @Test
        @DisplayName("Missing tokenUri → false")
        void missingTokenUri_returnsFalse() {
            var config = new DownstreamOAuth2BearerGatewayFilterFactory.Config();
            config.setClientId("client");
            config.setClientSecret("secret");
            assertThat(config.hasDirectOAuth2Config()).isFalse();
        }

        @Test
        @DisplayName("Blank clientId → false")
        void blankClientId_returnsFalse() {
            var config = new DownstreamOAuth2BearerGatewayFilterFactory.Config();
            config.setTokenUri("https://auth.example.com/token");
            config.setClientId("");
            config.setClientSecret("secret");
            assertThat(config.hasDirectOAuth2Config()).isFalse();
        }

        @Test
        @DisplayName("Missing clientSecret → false")
        void missingClientSecret_returnsFalse() {
            var config = new DownstreamOAuth2BearerGatewayFilterFactory.Config();
            config.setTokenUri("https://auth.example.com/token");
            config.setClientId("client");
            assertThat(config.hasDirectOAuth2Config()).isFalse();
        }

        @Test
        @DisplayName("No direct fields → false, falls back to named provider")
        void noDirectFields_returnsFalse() {
            var config = new DownstreamOAuth2BearerGatewayFilterFactory.Config();
            config.setOauth2ProviderName("my-provider");
            assertThat(config.hasDirectOAuth2Config()).isFalse();
        }
    }

    @Test
    @DisplayName("Factory order is 1")
    void factoryOrder_is1() {
        assertThat(factory.getOrder()).isEqualTo(1);
    }
}

