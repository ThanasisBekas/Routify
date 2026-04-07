package io.routify.gateway.filter.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RateLimitKeyResolver}.
 *
 * <p>Covers all strategy variants including the new ROUTE, HEADER, and COMPOSITE strategies,
 * as well as edge cases (null headers, missing attributes, unknown strategies).
 */
class RateLimitKeyResolverTest {

    private RateLimitKeyResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new RateLimitKeyResolver();
    }

    // ─── IP Strategy ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("IP strategy")
    class IpStrategy {

        @Test
        @DisplayName("resolves from X-Forwarded-For when present")
        void resolvesFromXForwardedFor() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                    .header("X-Forwarded-For", "203.0.113.50, 70.41.3.18")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            String key = resolver.resolve(exchange, "IP");

            assertThat(key).isEqualTo("203.0.113.50");
        }

        @Test
        @DisplayName("falls back to remoteAddress when X-Forwarded-For absent")
        void fallsBackToRemoteAddress() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/test").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            String key = resolver.resolve(exchange, "IP");

            // MockServerHttpRequest has a default remote address of "localhost"
            assertThat(key).isNotBlank();
        }

        @Test
        @DisplayName("default strategy is IP when null is passed")
        void defaultStrategyIsIp() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/test").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            String key = resolver.resolve(exchange, null);

            assertThat(key).isNotBlank();
        }
    }

    // ─── USER Strategy ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("USER strategy")
    class UserStrategy {

        @Test
        @DisplayName("resolves from X-Auth-User-Id header")
        void resolvesFromAuthUserIdHeader() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                    .header("X-Auth-User-Id", "user-123")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            String key = resolver.resolve(exchange, "USER");

            assertThat(key).isEqualTo("user:user-123");
        }

        @Test
        @DisplayName("returns 'anonymous' when user header missing")
        void returnsAnonymousWhenMissing() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/test").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            String key = resolver.resolve(exchange, "USER");

            assertThat(key).isEqualTo("anonymous");
        }
    }

    // ─── TENANT Strategy ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("TENANT strategy")
    class TenantStrategy {

        @Test
        @DisplayName("resolves from X-Tenant-Id header")
        void resolvesFromTenantIdHeader() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                    .header("X-Tenant-Id", "tenant-abc")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            String key = resolver.resolve(exchange, "TENANT");

            assertThat(key).isEqualTo("tenant:tenant-abc");
        }

        @Test
        @DisplayName("returns 'unknown-tenant' when tenant header missing")
        void returnsUnknownTenantWhenMissing() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/test").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            String key = resolver.resolve(exchange, "TENANT");

            assertThat(key).isEqualTo("unknown-tenant");
        }
    }

    // ─── API_KEY Strategy ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("API_KEY strategy")
    class ApiKeyStrategy {

        @Test
        @DisplayName("resolves from X-Api-Key header")
        void resolvesFromApiKeyHeader() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                    .header("X-Api-Key", "my-secret-key")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            String key = resolver.resolve(exchange, "API_KEY");

            assertThat(key).startsWith("apikey:");
        }

        @Test
        @DisplayName("resolves from apiKey query parameter when header absent")
        void resolvesFromQueryParam() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/test?apiKey=query-key").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            String key = resolver.resolve(exchange, "API_KEY");

            assertThat(key).startsWith("apikey:");
        }

        @Test
        @DisplayName("returns 'no-key' when both header and query param absent")
        void returnsNoKeyWhenMissing() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/test").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            String key = resolver.resolve(exchange, "API_KEY");

            assertThat(key).isEqualTo("no-key");
        }
    }

    // ─── TENANT_USER Strategy ──────────────────────────────────────────────────

    @Nested
    @DisplayName("TENANT_USER strategy")
    class TenantUserStrategy {

        @Test
        @DisplayName("concatenates tenant and user IDs")
        void concatenatesTenantAndUser() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                    .header("X-Tenant-Id", "tenant-abc")
                    .header("X-Auth-User-Id", "user-123")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            String key = resolver.resolve(exchange, "TENANT_USER");

            assertThat(key).isEqualTo("tenant-abc:user-123");
        }

        @Test
        @DisplayName("uses defaults when headers missing")
        void usesDefaultsWhenMissing() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/test").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            String key = resolver.resolve(exchange, "TENANT_USER");

            assertThat(key).isEqualTo("unknown:anonymous");
        }
    }

    // ─── ROUTE Strategy ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ROUTE strategy")
    class RouteStrategy {

        @Test
        @DisplayName("resolves from exchange route attribute")
        void resolvesFromRouteAttribute() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/test").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            Route route = mock(Route.class);
            when(route.getId()).thenReturn("tenant-1::route-42");
            exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route);

            String key = resolver.resolve(exchange, "ROUTE");

            assertThat(key).isEqualTo("route:tenant-1::route-42");
        }

        @Test
        @DisplayName("falls back to IP when route attribute missing")
        void fallsBackToIpWhenMissing() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/test").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            String key = resolver.resolve(exchange, "ROUTE");

            // Should fall back to IP since no route attribute is set
            assertThat(key).isNotBlank();
            assertThat(key).doesNotStartWith("route:");
        }
    }

    // ─── HEADER:<name> Strategy ────────────────────────────────────────────────

    @Nested
    @DisplayName("HEADER:<name> strategy")
    class HeaderStrategy {

        @Test
        @DisplayName("resolves arbitrary header value")
        void resolvesArbitraryHeader() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                    .header("X-Custom-Key", "custom-value-123")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            String key = resolver.resolve(exchange, "HEADER:X-Custom-Key");

            assertThat(key).isEqualTo("header:X-Custom-Key:custom-value-123");
        }

        @Test
        @DisplayName("falls back to IP when header is absent")
        void fallsBackToIpWhenHeaderAbsent() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/test").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            String key = resolver.resolve(exchange, "HEADER:X-Custom-Key");

            assertThat(key).isNotBlank();
            assertThat(key).doesNotStartWith("header:");
        }

        @Test
        @DisplayName("falls back to IP when header name is empty")
        void fallsBackToIpWhenHeaderNameEmpty() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/test").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            String key = resolver.resolve(exchange, "HEADER:");

            assertThat(key).isNotBlank();
            assertThat(key).doesNotStartWith("header:");
        }
    }

    // ─── COMPOSITE:<a>:<b> Strategy ────────────────────────────────────────────

    @Nested
    @DisplayName("COMPOSITE:<a>:<b> strategy")
    class CompositeStrategy {

        @Test
        @DisplayName("concatenates TENANT and USER strategies")
        void concatenatesTenantAndUser() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                    .header("X-Tenant-Id", "t-1")
                    .header("X-Auth-User-Id", "u-2")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            String key = resolver.resolve(exchange, "COMPOSITE:TENANT:USER");

            assertThat(key).isEqualTo("tenant:t-1:user:u-2");
        }

        @Test
        @DisplayName("uses IP fallback when one part is null")
        void usesIpFallbackWhenPartIsNull() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                    .header("X-Tenant-Id", "t-1")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            // ROUTE will be null (no route attribute), so it should fall back to IP
            String key = resolver.resolve(exchange, "COMPOSITE:TENANT:ROUTE");

            assertThat(key).startsWith("tenant:t-1:");
        }

        @Test
        @DisplayName("falls back to IP for invalid COMPOSITE format")
        void fallsBackForInvalidFormat() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/test").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            // Missing second strategy
            String key = resolver.resolve(exchange, "COMPOSITE:TENANT");

            // Should still return something (IP fallback)
            assertThat(key).isNotBlank();
        }
    }

    // ─── Edge Cases ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("unknown strategy falls back to client IP")
        void unknownStrategyFallsBackToIp() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/test").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            String key = resolver.resolve(exchange, "UNKNOWN_STRATEGY");

            assertThat(key).isNotBlank();
        }

        @Test
        @DisplayName("strategy is case-insensitive")
        void strategyIsCaseInsensitive() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                    .header("X-Tenant-Id", "tenant-abc")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            String keyLower = resolver.resolve(exchange, "tenant");
            String keyUpper = resolver.resolve(exchange, "TENANT");
            String keyMixed = resolver.resolve(exchange, "Tenant");

            assertThat(keyLower).isEqualTo(keyUpper).isEqualTo(keyMixed);
        }

        @Test
        @DisplayName("empty string strategy defaults to IP")
        void emptyStringDefaultsToIp() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/test").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            String key = resolver.resolve(exchange, "");

            assertThat(key).isNotBlank();
        }

        @Test
        @DisplayName("whitespace-only strategy defaults to IP")
        void whitespaceOnlyDefaultsToIp() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/test").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            String key = resolver.resolve(exchange, "   ");

            assertThat(key).isNotBlank();
        }

        @Test
        @DisplayName("backward compatibility — existing configs work unchanged")
        void backwardCompatibility() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                    .header("X-Auth-User-Id", "user-42")
                    .header("X-Tenant-Id", "tenant-7")
                    .header("X-Api-Key", "key-99")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            assertThat(resolver.resolve(exchange, "IP")).isNotBlank();
            assertThat(resolver.resolve(exchange, "USER")).isEqualTo("user:user-42");
            assertThat(resolver.resolve(exchange, "TENANT")).isEqualTo("tenant:tenant-7");
            assertThat(resolver.resolve(exchange, "API_KEY")).startsWith("apikey:");
            assertThat(resolver.resolve(exchange, "TENANT_USER")).isEqualTo("tenant-7:user-42");
        }
    }
}

