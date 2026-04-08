package io.routify.gateway.filter.auth;

import io.routify.gateway.auth.properties.AuthProperties;
import io.routify.gateway.auth.properties.ParameterStyle;
import io.routify.gateway.downstream.oauth2.Oauth2BearerTokenVerifier;
import io.routify.gateway.downstream.oauth2.TokenVerificationResponse;
import io.routify.gateway.filter.OAuth2TokenIntrospectGatewayFilterFactory;
import io.routify.gateway.filter.OAuth2TokenIntrospectGatewayFilterFactory.Config;
import io.routify.gateway.routing.GatewayConfigRefResolver;
import io.routify.gateway.config.GatewayConfigLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P-04 OAuth2 Dynamic Config Bridge — dedicated tests for the dual-path
 * token introspection in {@link OAuth2TokenIntrospectGatewayFilterFactory}
 * and the updated {@link GatewayConfigRefResolver} provider name mapping.
 *
 * <p>Covers:
 * <ul>
 *   <li>Direct config (introspectUri/clientId/clientSecret) takes the dynamic path</li>
 *   <li>Legacy providerName → AuthProperties path still works</li>
 *   <li>Direct config takes precedence when both are present</li>
 *   <li>Neither path configured → OAUTH2_MISCONFIGURED error</li>
 *   <li>Missing bearer token → BAD_REQUEST</li>
 *   <li>Introspection failure → error propagated</li>
 *   <li>Successful auth strips Authorization, maps claims to headers</li>
 *   <li>GatewayConfigRefResolver maps providerName + _providerName for OAUTH2 types</li>
 * </ul>
 */
class OAuth2DynamicConfigBridgeTest {

    private Oauth2BearerTokenVerifier bearerTokenVerifier;
    private AuthProperties authProperties;
    private OAuth2TokenIntrospectGatewayFilterFactory factory;

    @BeforeEach
    void setUp() {
        bearerTokenVerifier = mock(Oauth2BearerTokenVerifier.class);
        authProperties = new AuthProperties();
        factory = new OAuth2TokenIntrospectGatewayFilterFactory(bearerTokenVerifier, authProperties);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private GatewayFilterChain passThroughChain() {
        return exchange -> Mono.empty();
    }

    private MockServerWebExchange exchangeWithBearer(String token) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build());
    }

    private MockServerWebExchange exchangeWithoutAuth() {
        return MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test").build());
    }

    private TokenVerificationResponse successResponse() {
        return new TokenVerificationResponse(200, "{\"active\":true}",
                Map.of("sub", "user-123", "email", "test@test.com"));
    }

    private TokenVerificationResponse failureResponse() {
        return new TokenVerificationResponse(401, "Unauthorized", Map.of());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Direct config path (P-04 new path)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Direct config path (introspectUri/clientId/clientSecret)")
    class DirectConfigPath {

        @Test
        @DisplayName("Direct config with all fields → calls dynamic verifyToken, passes through on 200")
        void directConfig_success() {
            Config config = new Config();
            config.setIntrospectUri("https://auth.example.com/introspect");
            config.setClientId("my-client");
            config.setClientSecret("my-secret");
            config.setClaimsToHeaderMapping(Map.of("sub", "X-User-Id"));

            when(bearerTokenVerifier.verifyToken(
                    eq("https://auth.example.com/introspect"),
                    eq("my-client"), eq("my-secret"),
                    any(ParameterStyle.class), any(), any(), anyBoolean(),
                    eq("test-token")))
                    .thenReturn(Mono.just(successResponse()));

            GatewayFilter filter = factory.apply(config);
            var exchange = exchangeWithBearer("test-token");

            // Use a chain that captures the mutated request
            final String[] capturedUserId = {null};
            GatewayFilterChain capturingChain = ex -> {
                capturedUserId[0] = ex.getRequest().getHeaders().getFirst("X-User-Id");
                return Mono.empty();
            };

            StepVerifier.create(filter.filter(exchange, capturingChain))
                    .verifyComplete();

            // Should pass through (no error status)
            assertThat(exchange.getResponse().getStatusCode()).isNull();
            // Claims mapped to headers
            assertThat(capturedUserId[0]).isEqualTo("user-123");

            // Verify the dynamic overload was called, NOT the legacy one
            verify(bearerTokenVerifier).verifyToken(
                    eq("https://auth.example.com/introspect"),
                    eq("my-client"), eq("my-secret"),
                    any(ParameterStyle.class), any(), any(), anyBoolean(),
                    eq("test-token"));
            verify(bearerTokenVerifier, never()).verifyToken(anyString(), eq("test-token"));
        }

        @Test
        @DisplayName("Direct config — introspection returns 401 → error propagated")
        void directConfig_introspectionFails() {
            Config config = new Config();
            config.setIntrospectUri("https://auth.example.com/introspect");
            config.setClientId("my-client");
            config.setClientSecret("my-secret");

            when(bearerTokenVerifier.verifyToken(
                    anyString(), anyString(), anyString(),
                    any(ParameterStyle.class), any(), any(), anyBoolean(),
                    eq("bad-token")))
                    .thenReturn(Mono.just(failureResponse()));

            GatewayFilter filter = factory.apply(config);
            var exchange = exchangeWithBearer("bad-token");

            StepVerifier.create(filter.filter(exchange, passThroughChain()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("Direct config takes precedence when both introspectUri AND providerName are set")
        void directConfig_takesPrecedenceOverProviderName() {
            Config config = new Config();
            config.setIntrospectUri("https://auth.example.com/introspect");
            config.setClientId("my-client");
            config.setClientSecret("my-secret");
            config.setProviderName("legacy-provider");

            when(bearerTokenVerifier.verifyToken(
                    eq("https://auth.example.com/introspect"),
                    anyString(), anyString(),
                    any(ParameterStyle.class), any(), any(), anyBoolean(),
                    eq("token123")))
                    .thenReturn(Mono.just(successResponse()));

            GatewayFilter filter = factory.apply(config);
            var exchange = exchangeWithBearer("token123");

            StepVerifier.create(filter.filter(exchange, passThroughChain()))
                    .verifyComplete();

            // Dynamic overload should have been called
            verify(bearerTokenVerifier).verifyToken(
                    eq("https://auth.example.com/introspect"),
                    anyString(), anyString(),
                    any(ParameterStyle.class), any(), any(), anyBoolean(),
                    eq("token123"));
            // Legacy overload should NOT be called
            verify(bearerTokenVerifier, never()).verifyToken(eq("legacy-provider"), anyString());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Legacy providerName path
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Legacy providerName path (AuthProperties YAML)")
    class LegacyProviderNamePath {

        @Test
        @DisplayName("providerName with valid AuthProperties config → passes through on 200")
        void legacyProvider_success() {
            // Register a provider in AuthProperties
            AuthProperties.Oauth2VerificationConfig verConfig = new AuthProperties.Oauth2VerificationConfig();
            verConfig.setUri("https://legacy.auth.com/introspect");
            verConfig.setParameterStyle(ParameterStyle.BODY);
            verConfig.setParameterName("token");
            authProperties.getOauth2Verification().put("my-legacy-provider", verConfig);

            Config config = new Config();
            config.setProviderName("my-legacy-provider");

            when(bearerTokenVerifier.verifyToken(eq("my-legacy-provider"), eq("my-token")))
                    .thenReturn(Mono.just(successResponse()));

            GatewayFilter filter = factory.apply(config);
            var exchange = exchangeWithBearer("my-token");

            StepVerifier.create(filter.filter(exchange, passThroughChain()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isNull();
            verify(bearerTokenVerifier).verifyToken("my-legacy-provider", "my-token");
        }

        @Test
        @DisplayName("providerName with no AuthProperties entry → OAUTH2_MISCONFIGURED")
        void legacyProvider_missingConfig() {
            Config config = new Config();
            config.setProviderName("nonexistent-provider");

            GatewayFilter filter = factory.apply(config);
            var exchange = exchangeWithBearer("some-token");

            StepVerifier.create(filter.filter(exchange, passThroughChain()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Edge cases
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Neither introspectUri nor providerName → OAUTH2_MISCONFIGURED")
    void neitherConfigured_returns401() {
        Config config = new Config();
        // Both null

        GatewayFilter filter = factory.apply(config);
        var exchange = exchangeWithBearer("some-token");

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Missing Bearer token → 400 MISSING_BEARER_TOKEN")
    void missingBearerToken_returns400() {
        Config config = new Config();
        config.setIntrospectUri("https://auth.example.com/introspect");
        config.setClientId("client");
        config.setClientSecret("secret");

        GatewayFilter filter = factory.apply(config);
        var exchange = exchangeWithoutAuth();

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(bearerTokenVerifier);
    }

    @Test
    @DisplayName("Partial direct config (missing clientSecret) → falls back to providerName")
    void partialDirectConfig_fallsBackToProviderName() {
        // introspectUri set but clientSecret missing → incomplete direct config
        Config config = new Config();
        config.setIntrospectUri("https://auth.example.com/introspect");
        config.setClientId("my-client");
        // clientSecret is null
        config.setProviderName("fallback-provider");

        // Register fallback provider
        AuthProperties.Oauth2VerificationConfig verConfig = new AuthProperties.Oauth2VerificationConfig();
        verConfig.setUri("https://fallback.auth.com/introspect");
        verConfig.setParameterStyle(ParameterStyle.BODY);
        verConfig.setParameterName("token");
        authProperties.getOauth2Verification().put("fallback-provider", verConfig);

        when(bearerTokenVerifier.verifyToken(eq("fallback-provider"), eq("my-token")))
                .thenReturn(Mono.just(successResponse()));

        GatewayFilter filter = factory.apply(config);
        var exchange = exchangeWithBearer("my-token");

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        // Should use the legacy path
        verify(bearerTokenVerifier).verifyToken("fallback-provider", "my-token");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GatewayConfigRefResolver provider name mapping
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GatewayConfigRefResolver — providerName mapping for OAUTH2 types")
    class ConfigRefResolverMapping {

        @Test
        @DisplayName("OAUTH2_INTROSPECT provider → maps providerName alongside _providerName and introspection fields")
        void oauth2Provider_mapsProviderName() {
            GatewayConfigLoader configLoader = mock(GatewayConfigLoader.class);
            var resolver = new GatewayConfigRefResolver(configLoader);

            // Simulate a gateway config with one OAuth2 provider
            Map<String, Object> provider = new LinkedHashMap<>();
            provider.put("id", "provider-uuid-1");
            provider.put("name", "my-oauth2-provider");
            provider.put("type", "OAUTH2_INTROSPECT");
            provider.put("uri", "https://idp.example.com/introspect");
            provider.put("clientId", "gw-client");
            provider.put("clientSecret", "gw-secret");
            provider.put("scope", "openid");
            provider.put("parameterStyle", "BODY");
            provider.put("parameterName", "token");

            Map<String, Object> gwConfig = Map.of(
                    "authProviders", List.of(provider));
            when(configLoader.getConfig()).thenReturn(gwConfig);

            Map<String, Object> ref = Map.of(
                    "refType", "AUTH_PROVIDER",
                    "refId", "provider-uuid-1",
                    "refName", "my-oauth2-provider");

            Map<String, Object> resolved = resolver.resolve(Map.of(), ref);

            // Should have providerName mapped from the name field
            assertThat(resolved).containsEntry("providerName", "my-oauth2-provider");
            assertThat(resolved).containsEntry("_providerName", "my-oauth2-provider");
            assertThat(resolved).containsEntry("_providerType", "OAUTH2_INTROSPECT");

            // Should have introspection fields
            assertThat(resolved).containsEntry("introspectUri", "https://idp.example.com/introspect");
            assertThat(resolved).containsEntry("clientId", "gw-client");
            assertThat(resolved).containsEntry("clientSecret", "gw-secret");
            assertThat(resolved).containsEntry("scope", "openid");

            // Should have introspection-specific fields
            assertThat(resolved).containsEntry("parameterStyle", "BODY");
            assertThat(resolved).containsEntry("parameterName", "token");
        }

        @Test
        @DisplayName("BASIC provider → still maps providerName and username/password (not introspection fields)")
        void basicProvider_mapsProviderName() {
            GatewayConfigLoader configLoader = mock(GatewayConfigLoader.class);
            var resolver = new GatewayConfigRefResolver(configLoader);

            Map<String, Object> provider = new LinkedHashMap<>();
            provider.put("id", "basic-1");
            provider.put("name", "my-basic-provider");
            provider.put("type", "BASIC");
            provider.put("username", "admin");
            provider.put("password", "$2a$12$hashed");

            Map<String, Object> gwConfig = Map.of(
                    "authProviders", List.of(provider));
            when(configLoader.getConfig()).thenReturn(gwConfig);

            Map<String, Object> ref = Map.of(
                    "refType", "AUTH_PROVIDER",
                    "refId", "basic-1",
                    "refName", "my-basic-provider");

            Map<String, Object> resolved = resolver.resolve(Map.of(), ref);

            assertThat(resolved).containsEntry("providerName", "my-basic-provider");
            assertThat(resolved).containsEntry("_providerName", "my-basic-provider");
            assertThat(resolved).containsEntry("username", "admin");
            assertThat(resolved).containsEntry("password", "$2a$12$hashed");
            // Should NOT have OAuth2 fields
            assertThat(resolved).doesNotContainKey("introspectUri");
        }
    }
}

