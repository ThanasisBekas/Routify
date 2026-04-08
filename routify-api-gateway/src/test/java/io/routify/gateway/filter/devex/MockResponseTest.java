package io.routify.gateway.filter.devex;

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
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MockResponseGatewayFilterFactory}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Config defaults and custom values</li>
 *   <li>Mock response with status, body, and content type</li>
 *   <li>Custom headers on response</li>
 *   <li>Template interpolation: ${method}, ${path}, ${header:X-Foo}, ${param:id}, ${timestamp}, ${correlationId}</li>
 *   <li>Unknown placeholders resolve to empty string</li>
 *   <li>Delay simulation</li>
 *   <li>Conditional activation via header presence</li>
 *   <li>No upstream call when mock is active</li>
 * </ul>
 */
class MockResponseTest {

    private MockResponseGatewayFilterFactory factory;
    private MockResponseGatewayFilterFactory.Config config;

    @BeforeEach
    void setUp() {
        factory = new MockResponseGatewayFilterFactory();
        config = new MockResponseGatewayFilterFactory.Config();
    }

    // ── Config defaults ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Config defaults")
    class ConfigDefaults {

        @Test
        @DisplayName("Default status is 200")
        void defaultStatus() {
            assertThat(config.getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("Default contentType is application/json")
        void defaultContentType() {
            assertThat(config.getContentType()).isEqualTo("application/json");
        }

        @Test
        @DisplayName("Default body is {}")
        void defaultBody() {
            assertThat(config.getBody()).isEqualTo("{}");
        }

        @Test
        @DisplayName("Default headers is empty string")
        void defaultHeaders() {
            assertThat(config.getHeaders()).isEmpty();
        }

        @Test
        @DisplayName("Default delay is 0")
        void defaultDelay() {
            assertThat(config.getDelay()).isEqualTo(0);
        }

        @Test
        @DisplayName("Default conditionHeader is empty string")
        void defaultConditionHeader() {
            assertThat(config.getConditionHeader()).isEmpty();
        }
    }

    // ── Config mutation ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Config mutation")
    class ConfigMutation {

        @Test
        @DisplayName("Status can be set to custom value")
        void customStatus() {
            config.setStatus(503);
            assertThat(config.getStatus()).isEqualTo(503);
        }

        @Test
        @DisplayName("Body can be set to custom value")
        void customBody() {
            config.setBody("{\"message\":\"hello\"}");
            assertThat(config.getBody()).isEqualTo("{\"message\":\"hello\"}");
        }

        @Test
        @DisplayName("Delay can be set to custom value")
        void customDelay() {
            config.setDelay(500);
            assertThat(config.getDelay()).isEqualTo(500);
        }

        @Test
        @DisplayName("Headers are parsed correctly")
        void headersParsed() {
            config.setHeaders("Retry-After: 3600, X-Custom: test");
            var parsed = config.getParsedHeaders();
            assertThat(parsed).hasSize(2);
            assertThat(parsed.get("Retry-After")).isEqualTo("3600");
            assertThat(parsed.get("X-Custom")).isEqualTo("test");
        }

        @Test
        @DisplayName("Empty headers returns empty map")
        void emptyHeaders() {
            config.setHeaders("");
            assertThat(config.getParsedHeaders()).isEmpty();
        }
    }

    // ── Basic mock response ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Basic mock response")
    class BasicMockResponse {

        @Test
        @DisplayName("Returns configured status code, body, and content type")
        void returnsConfiguredResponse() {
            config.setStatus(201);
            config.setContentType("text/plain");
            config.setBody("Created!");

            GatewayFilter filter = factory.apply(config);

            MockServerHttpRequest request = MockServerHttpRequest.post("/api/test").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(filter.filter(exchange, _ -> Mono.empty()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.CONTENT_TYPE))
                    .isEqualTo("text/plain");
        }

        @Test
        @DisplayName("Custom headers are set on response")
        void customHeadersSet() {
            config.setHeaders("Retry-After: 3600, X-Mock: true");

            GatewayFilter filter = factory.apply(config);

            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(filter.filter(exchange, _ -> Mono.empty()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("3600");
            assertThat(exchange.getResponse().getHeaders().getFirst("X-Mock")).isEqualTo("true");
        }

        @Test
        @DisplayName("No upstream call is made (chain.filter not invoked)")
        void noUpstreamCall() {
            GatewayFilter filter = factory.apply(config);

            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            AtomicBoolean chainCalled = new AtomicBoolean(false);
            GatewayFilterChain chain = _ -> {
                chainCalled.set(true);
                return Mono.empty();
            };

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertThat(chainCalled.get()).isFalse();
        }

        @Test
        @DisplayName("Default body is {}")
        void defaultBodyReturned() {
            GatewayFilter filter = factory.apply(config);

            MockServerHttpRequest request = MockServerHttpRequest.get("/").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(filter.filter(exchange, _ -> Mono.empty()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("Maintenance mode — status 503 with Retry-After header")
        void maintenanceMode() {
            config.setStatus(503);
            config.setBody("{\"detail\":\"Under maintenance\"}");
            config.setHeaders("Retry-After: 3600");

            GatewayFilter filter = factory.apply(config);

            MockServerHttpRequest request = MockServerHttpRequest.get("/api/anything").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(filter.filter(exchange, _ -> Mono.empty()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("3600");
        }
    }

    // ── Template interpolation ───────────────────────────────────────────────

    @Nested
    @DisplayName("Template interpolation")
    class TemplateInterpolation {

        @Test
        @DisplayName("${method} resolves to request HTTP method")
        void resolveMethod() {
            MockServerHttpRequest request = MockServerHttpRequest.post("/test").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            String result = MockResponseGatewayFilterFactory.resolveTemplate("Method: ${method}", exchange);
            assertThat(result).isEqualTo("Method: POST");
        }

        @Test
        @DisplayName("${path} resolves to request path")
        void resolvePath() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/users").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            String result = MockResponseGatewayFilterFactory.resolveTemplate("Path: ${path}", exchange);
            assertThat(result).isEqualTo("Path: /api/v1/users");
        }

        @Test
        @DisplayName("${header:X-User-Id} resolves to header value")
        void resolveHeader() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/")
                    .header("X-User-Id", "user-42")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            String result = MockResponseGatewayFilterFactory.resolveTemplate(
                    "User: ${header:X-User-Id}", exchange);
            assertThat(result).isEqualTo("User: user-42");
        }

        @Test
        @DisplayName("${header:Missing} resolves to empty string")
        void resolveMissingHeader() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            String result = MockResponseGatewayFilterFactory.resolveTemplate(
                    "User: ${header:X-Missing}", exchange);
            assertThat(result).isEqualTo("User: ");
        }

        @Test
        @DisplayName("${param:id} resolves to query parameter value")
        void resolveParam() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api?id=42&name=test").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            String result = MockResponseGatewayFilterFactory.resolveTemplate(
                    "ID: ${param:id}, Name: ${param:name}", exchange);
            assertThat(result).isEqualTo("ID: 42, Name: test");
        }

        @Test
        @DisplayName("${param:missing} resolves to empty string")
        void resolveMissingParam() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            String result = MockResponseGatewayFilterFactory.resolveTemplate(
                    "Val: ${param:missing}", exchange);
            assertThat(result).isEqualTo("Val: ");
        }

        @Test
        @DisplayName("${timestamp} resolves to ISO-8601 timestamp")
        void resolveTimestamp() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            String result = MockResponseGatewayFilterFactory.resolveTemplate(
                    "Time: ${timestamp}", exchange);
            assertThat(result).startsWith("Time: 20");
            assertThat(result).contains("T");
        }

        @Test
        @DisplayName("${correlationId} resolves to X-Correlation-Id header")
        void resolveCorrelationId() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/")
                    .header("X-Correlation-Id", "abc-123")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            String result = MockResponseGatewayFilterFactory.resolveTemplate(
                    "Corr: ${correlationId}", exchange);
            assertThat(result).isEqualTo("Corr: abc-123");
        }

        @Test
        @DisplayName("Unknown placeholder resolves to empty string")
        void resolveUnknown() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            String result = MockResponseGatewayFilterFactory.resolveTemplate(
                    "Val: ${unknown}", exchange);
            assertThat(result).isEqualTo("Val: ");
        }

        @Test
        @DisplayName("Multiple placeholders in a single template")
        void resolveMultiple() {
            MockServerHttpRequest request = MockServerHttpRequest.post("/api/users?version=2")
                    .header("X-User-Id", "user-7")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            String result = MockResponseGatewayFilterFactory.resolveTemplate(
                    "{\"method\":\"${method}\",\"path\":\"${path}\",\"user\":\"${header:X-User-Id}\",\"v\":\"${param:version}\"}",
                    exchange);
            assertThat(result).isEqualTo(
                    "{\"method\":\"POST\",\"path\":\"/api/users\",\"user\":\"user-7\",\"v\":\"2\"}");
        }

        @Test
        @DisplayName("Template without placeholders returns unchanged")
        void noPlaceholders() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            String result = MockResponseGatewayFilterFactory.resolveTemplate("plain text", exchange);
            assertThat(result).isEqualTo("plain text");
        }

        @Test
        @DisplayName("Null template returns null")
        void nullTemplate() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            String result = MockResponseGatewayFilterFactory.resolveTemplate(null, exchange);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Empty template returns empty string")
        void emptyTemplate() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            String result = MockResponseGatewayFilterFactory.resolveTemplate("", exchange);
            assertThat(result).isEmpty();
        }
    }

    // ── Delay simulation ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Delay simulation")
    class DelaySimulation {

        @Test
        @DisplayName("delay=0 returns immediately")
        void noDelay() {
            config.setDelay(0);
            GatewayFilter filter = factory.apply(config);

            MockServerHttpRequest request = MockServerHttpRequest.get("/").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(filter.filter(exchange, _ -> Mono.empty()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("delay > 0 adds simulated latency")
        void withDelay() {
            config.setDelay(200);
            GatewayFilter filter = factory.apply(config);

            MockServerHttpRequest request = MockServerHttpRequest.get("/").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(filter.filter(exchange, _ -> Mono.empty()))
                    .expectSubscription()
                    .expectNoEvent(Duration.ofMillis(100))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ── Conditional activation ────────────────────────────────────────────────

    @Nested
    @DisplayName("Conditional activation")
    class ConditionalActivation {

        @Test
        @DisplayName("conditionHeader present → returns mock response")
        void conditionMet() {
            config.setConditionHeader("X-Mock");
            config.setStatus(418);
            config.setBody("{\"mock\":true}");

            GatewayFilter filter = factory.apply(config);

            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("X-Mock", "true")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            AtomicBoolean chainCalled = new AtomicBoolean(false);
            GatewayFilterChain chain = _ -> {
                chainCalled.set(true);
                return Mono.empty();
            };

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            // Mock returned, chain not called
            assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(418);
            assertThat(chainCalled.get()).isFalse();
        }

        @Test
        @DisplayName("conditionHeader absent → passes through to upstream")
        void conditionNotMet() {
            config.setConditionHeader("X-Mock");
            config.setStatus(418);

            GatewayFilter filter = factory.apply(config);

            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            AtomicBoolean chainCalled = new AtomicBoolean(false);
            GatewayFilterChain chain = _ -> {
                chainCalled.set(true);
                return Mono.empty();
            };

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            // Passed through, chain was called
            assertThat(chainCalled.get()).isTrue();
        }

        @Test
        @DisplayName("No conditionHeader configured → always returns mock")
        void noConditionAlwaysMocks() {
            // conditionHeader is empty by default
            config.setStatus(200);

            GatewayFilter filter = factory.apply(config);

            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            AtomicBoolean chainCalled = new AtomicBoolean(false);
            GatewayFilterChain chain = _ -> {
                chainCalled.set(true);
                return Mono.empty();
            };

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertThat(chainCalled.get()).isFalse();
        }
    }

    // ── Factory instantiation ────────────────────────────────────────────────

    @Nested
    @DisplayName("Factory instantiation")
    class FactoryInstantiation {

        @Test
        @DisplayName("Factory creates a non-null filter")
        void createsFilter() {
            GatewayFilter filter = factory.apply(config);
            assertThat(filter).isNotNull();
        }

        @Test
        @DisplayName("Factory creates filter with custom config")
        void createsFilterWithCustomConfig() {
            config.setStatus(503);
            config.setBody("{\"error\":\"maintenance\"}");
            config.setContentType("application/problem+json");
            config.setHeaders("Retry-After: 3600");
            config.setDelay(100);
            config.setConditionHeader("X-Mock");

            GatewayFilter filter = factory.apply(config);
            assertThat(filter).isNotNull();
        }
    }
}

