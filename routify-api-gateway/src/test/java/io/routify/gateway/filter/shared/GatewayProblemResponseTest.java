package io.routify.gateway.filter.shared;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GatewayProblemResponse}.
 *
 * <p>Verifies RFC 9457 ProblemDetail structure, JSON injection safety,
 * header propagation, and extension field support.
 */
class GatewayProblemResponseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private MockServerWebExchange createExchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/test").build());
    }

    private Map<String, Object> parseResponseBody(MockServerWebExchange exchange) throws Exception {
        byte[] bytes = exchange.getResponse().getBodyAsString()
                .block()
                .getBytes(StandardCharsets.UTF_8);
        return MAPPER.readValue(bytes, new TypeReference<>() {});
    }

    // ─── Standard ProblemDetail Fields ─────────────────────────────────────────

    @Test
    @DisplayName("Standard ProblemDetail fields serialized correctly")
    void standardFields_serializedCorrectly() throws Exception {
        MockServerWebExchange exchange = createExchange();

        StepVerifier.create(
                GatewayProblemResponse.status(HttpStatus.UNAUTHORIZED)
                        .errorCode("AUTH_FAILED")
                        .detail("Token is invalid")
                        .write(exchange)
        ).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        Map<String, Object> body = parseResponseBody(exchange);
        assertThat(body.get("type")).isEqualTo("about:blank");
        assertThat(body.get("title")).isEqualTo("Unauthorized");
        assertThat(body.get("status")).isEqualTo(401);
        assertThat(body.get("errorCode")).isEqualTo("AUTH_FAILED");
        assertThat(body.get("detail")).isEqualTo("Token is invalid");
    }

    @Test
    @DisplayName("Content-Type is always application/problem+json")
    void contentType_isProblemJson() {
        MockServerWebExchange exchange = createExchange();

        StepVerifier.create(
                GatewayProblemResponse.status(HttpStatus.FORBIDDEN)
                        .errorCode("FORBIDDEN")
                        .detail("Access denied")
                        .write(exchange)
        ).verifyComplete();

        String contentType = exchange.getResponse().getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        assertThat(contentType).isEqualTo("application/problem+json");
    }

    // ─── JSON Injection Tests ─────────────────────────────────────────────────

    @Test
    @DisplayName("Detail with double quotes is safely JSON-escaped")
    void detailWithQuotes_jsonEscaped() throws Exception {
        MockServerWebExchange exchange = createExchange();

        StepVerifier.create(
                GatewayProblemResponse.status(HttpStatus.BAD_REQUEST)
                        .errorCode("VALIDATION")
                        .detail("Field \"name\" is required")
                        .write(exchange)
        ).verifyComplete();

        Map<String, Object> body = parseResponseBody(exchange);
        assertThat(body.get("detail")).isEqualTo("Field \"name\" is required");
    }

    @Test
    @DisplayName("Detail with backslashes is safely JSON-escaped")
    void detailWithBackslashes_jsonEscaped() throws Exception {
        MockServerWebExchange exchange = createExchange();

        StepVerifier.create(
                GatewayProblemResponse.status(HttpStatus.BAD_REQUEST)
                        .errorCode("VALIDATION")
                        .detail("Path C:\\Users\\admin is invalid")
                        .write(exchange)
        ).verifyComplete();

        Map<String, Object> body = parseResponseBody(exchange);
        assertThat(body.get("detail")).isEqualTo("Path C:\\Users\\admin is invalid");
    }

    @Test
    @DisplayName("Detail with HTML/script tags is safely JSON-escaped")
    void detailWithHtmlScript_jsonEscaped() throws Exception {
        MockServerWebExchange exchange = createExchange();

        StepVerifier.create(
                GatewayProblemResponse.status(HttpStatus.BAD_REQUEST)
                        .errorCode("XSS_ATTEMPT")
                        .detail("<script>alert('xss')</script>")
                        .write(exchange)
        ).verifyComplete();

        Map<String, Object> body = parseResponseBody(exchange);
        assertThat(body.get("detail")).isEqualTo("<script>alert('xss')</script>");
    }

    @Test
    @DisplayName("Detail with Unicode characters is safely JSON-escaped")
    void detailWithUnicode_jsonEscaped() throws Exception {
        MockServerWebExchange exchange = createExchange();

        StepVerifier.create(
                GatewayProblemResponse.status(HttpStatus.BAD_REQUEST)
                        .errorCode("UNICODE")
                        .detail("User name: café ☕ 日本語")
                        .write(exchange)
        ).verifyComplete();

        Map<String, Object> body = parseResponseBody(exchange);
        assertThat(body.get("detail")).isEqualTo("User name: café ☕ 日本語");
    }

    @Test
    @DisplayName("Detail with newlines and control characters is safely JSON-escaped")
    void detailWithNewlines_jsonEscaped() throws Exception {
        MockServerWebExchange exchange = createExchange();

        StepVerifier.create(
                GatewayProblemResponse.status(HttpStatus.BAD_REQUEST)
                        .errorCode("CONTROL_CHARS")
                        .detail("Line 1\nLine 2\tTabbed\rReturn")
                        .write(exchange)
        ).verifyComplete();

        Map<String, Object> body = parseResponseBody(exchange);
        assertThat(body.get("detail")).isEqualTo("Line 1\nLine 2\tTabbed\rReturn");
    }

    @Test
    @DisplayName("Detail with combined injection payload is safely escaped")
    void detailWithCombinedInjection_jsonEscaped() throws Exception {
        MockServerWebExchange exchange = createExchange();
        // Combined attack: JSON injection + HTML + control chars
        String malicious = "{\"injected\":true}\",\"admin\":\"hacked\"}<script>alert(1)</script>";

        StepVerifier.create(
                GatewayProblemResponse.status(HttpStatus.FORBIDDEN)
                        .errorCode("INJECTED")
                        .detail(malicious)
                        .write(exchange)
        ).verifyComplete();

        Map<String, Object> body = parseResponseBody(exchange);
        assertThat(body.get("detail")).isEqualTo(malicious);
        // Verify the raw JSON is parseable and doesn't contain unescaped JSON
        String rawBody = exchange.getResponse().getBodyAsString().block();
        assertThat(rawBody).isNotNull();
        // Verify re-parsing succeeds (proves no JSON injection)
        Map<String, Object> reparsed = MAPPER.readValue(rawBody, new TypeReference<>() {});
        assertThat(reparsed.get("detail")).isEqualTo(malicious);
    }

    // ─── Custom Headers ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Custom headers are set on the response")
    void customHeaders_setOnResponse() {
        MockServerWebExchange exchange = createExchange();

        StepVerifier.create(
                GatewayProblemResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                        .errorCode("RATE_LIMIT_EXCEEDED")
                        .detail("Too many requests")
                        .header("Retry-After", "60")
                        .header("X-RateLimit-Window", "60000ms")
                        .write(exchange)
        ).verifyComplete();

        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("60");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Window")).isEqualTo("60000ms");
    }

    @Test
    @DisplayName("Retry-After header present on 429 responses")
    void retryAfterHeader_on429() {
        MockServerWebExchange exchange = createExchange();

        StepVerifier.create(
                GatewayProblemResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                        .errorCode("RATE_LIMIT_EXCEEDED")
                        .detail("Rate limit exceeded")
                        .header("Retry-After", 30)
                        .write(exchange)
        ).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("30");
    }

    @Test
    @DisplayName("WWW-Authenticate header for Basic auth responses")
    void wwwAuthenticate_forBasicAuth() {
        MockServerWebExchange exchange = createExchange();

        StepVerifier.create(
                GatewayProblemResponse.status(HttpStatus.UNAUTHORIZED)
                        .errorCode("MISSING_CREDENTIALS")
                        .detail("Authorization required")
                        .header("WWW-Authenticate", "Basic realm=\"Routify\"")
                        .write(exchange)
        ).verifyComplete();

        assertThat(exchange.getResponse().getHeaders().getFirst("WWW-Authenticate"))
                .isEqualTo("Basic realm=\"Routify\"");
    }

    // ─── Extension Fields ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Extension fields appear in response body")
    void extensionFields_inResponseBody() throws Exception {
        MockServerWebExchange exchange = createExchange();

        StepVerifier.create(
                GatewayProblemResponse.status(HttpStatus.FORBIDDEN)
                        .errorCode("AI_FILTER_BLOCKED")
                        .detail("Request blocked")
                        .extension("evaluationId", "eval-123-abc")
                        .write(exchange)
        ).verifyComplete();

        Map<String, Object> body = parseResponseBody(exchange);
        assertThat(body.get("evaluationId")).isEqualTo("eval-123-abc");
    }

    @Test
    @DisplayName("Violations list extension field serialized correctly")
    void violationsList_extensionField() throws Exception {
        MockServerWebExchange exchange = createExchange();
        List<String> violations = List.of(
                "Field 'name' is required",
                "Field 'age' must be >= 0"
        );

        StepVerifier.create(
                GatewayProblemResponse.status(HttpStatus.BAD_REQUEST)
                        .errorCode("JSON_SCHEMA_VALIDATION_FAILED")
                        .detail("Request body failed JSON Schema validation")
                        .extension("violations", violations)
                        .write(exchange)
        ).verifyComplete();

        Map<String, Object> body = parseResponseBody(exchange);
        assertThat(body.get("violations")).isEqualTo(violations);
    }

    // ─── Detail Format ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Detail with format args is interpolated correctly")
    void detailWithFormatArgs_interpolated() throws Exception {
        MockServerWebExchange exchange = createExchange();

        StepVerifier.create(
                GatewayProblemResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                        .errorCode("RATE_LIMIT_EXCEEDED")
                        .detail("Rate limit exceeded for key %s (window: %dms)", "10.0.0.1", 60000)
                        .write(exchange)
        ).verifyComplete();

        Map<String, Object> body = parseResponseBody(exchange);
        assertThat(body.get("detail")).isEqualTo("Rate limit exceeded for key 10.0.0.1 (window: 60000ms)");
    }

    // ─── Optional Fields ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Omitted optional fields are absent from response body")
    void omittedOptionalFields_absentFromBody() throws Exception {
        MockServerWebExchange exchange = createExchange();

        StepVerifier.create(
                GatewayProblemResponse.status(HttpStatus.FORBIDDEN)
                        .detail("Access denied")
                        .write(exchange)
        ).verifyComplete();

        Map<String, Object> body = parseResponseBody(exchange);
        assertThat(body).containsKey("type");
        assertThat(body).containsKey("title");
        assertThat(body).containsKey("status");
        assertThat(body).containsKey("detail");
        assertThat(body).doesNotContainKey("errorCode");
        assertThat(body).doesNotContainKey("instance");
    }

    @Test
    @DisplayName("Custom type and instance URIs are included")
    void typeAndInstance_included() throws Exception {
        MockServerWebExchange exchange = createExchange();

        StepVerifier.create(
                GatewayProblemResponse.status(HttpStatus.NOT_FOUND)
                        .type("https://routify.io/problems/route-not-found")
                        .instance("/api/v1/routes/123")
                        .detail("Route not found")
                        .write(exchange)
        ).verifyComplete();

        Map<String, Object> body = parseResponseBody(exchange);
        assertThat(body.get("type")).isEqualTo("https://routify.io/problems/route-not-found");
        assertThat(body.get("instance")).isEqualTo("/api/v1/routes/123");
    }

    // ─── Status Code Variations ───────────────────────────────────────────────

    @Test
    @DisplayName("Various HTTP status codes are correctly applied")
    void variousStatusCodes_applied() {
        for (HttpStatus status : List.of(
                HttpStatus.BAD_REQUEST,
                HttpStatus.UNAUTHORIZED,
                HttpStatus.FORBIDDEN,
                HttpStatus.NOT_FOUND,
                HttpStatus.TOO_MANY_REQUESTS,
                HttpStatus.BAD_GATEWAY,
                HttpStatus.SERVICE_UNAVAILABLE
        )) {
            MockServerWebExchange exchange = createExchange();
            StepVerifier.create(
                    GatewayProblemResponse.status(status)
                            .detail("Test")
                            .write(exchange)
            ).verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(status);
        }
    }

    @Test
    @DisplayName("Mono<Void> completes successfully (non-error signal)")
    void monoVoid_completesSuccessfully() {
        MockServerWebExchange exchange = createExchange();

        StepVerifier.create(
                GatewayProblemResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .errorCode("INTERNAL_ERROR")
                        .detail("Something went wrong")
                        .write(exchange)
        ).verifyComplete(); // no error signal
    }
}

