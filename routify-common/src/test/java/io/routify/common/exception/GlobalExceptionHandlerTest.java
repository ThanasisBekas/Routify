package io.routify.common.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.URI;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that {@link GlobalExceptionHandler} maps every {@link RoutifyException}
 * subtype to the correct RFC 9457 ProblemDetail response.
 *
 * <p>Validates: HTTP status, error code, type URI, title casing, path, timestamp,
 * and subtype-specific fields (e.g. {@code retryAfter} for {@link RoutifyException.RateLimitExceeded}).
 */
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private MockHttpServletRequest request;

    private static final URI PROBLEM_BASE = URI.create("https://routify.io/problems/");

    @BeforeEach
    void setup() {
        handler = new GlobalExceptionHandler();
        request = new MockHttpServletRequest("GET", "/api/v1/admin/routes");
    }

    // ─── Parameterised: all RoutifyException subtypes ─────────────────────────

    static Stream<Arguments> allExceptionSubtypes() {
        return Stream.of(
            Arguments.of(
                new RoutifyException.NotFound("Route", "abc-123"),
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                "resource-not-found",
                "Resource Not Found",
                "Route with id 'abc-123' not found"
            ),
            Arguments.of(
                new RoutifyException.Conflict("Route name already exists"),
                HttpStatus.CONFLICT,
                "CONFLICT",
                "conflict",
                "Conflict",
                "Route name already exists"
            ),
            Arguments.of(
                new RoutifyException.Validation("Path pattern is invalid"),
                HttpStatus.UNPROCESSABLE_ENTITY,
                "VALIDATION_ERROR",
                "validation-error",
                "Validation Error",
                "Path pattern is invalid"
            ),
            Arguments.of(
                new RoutifyException.BadRequest("Missing required field: name"),
                HttpStatus.BAD_REQUEST,
                "BAD_REQUEST",
                "bad-request",
                "Bad Request",
                "Missing required field: name"
            ),
            Arguments.of(
                new RoutifyException.Unauthorized("Invalid credentials"),
                HttpStatus.UNAUTHORIZED,
                "UNAUTHORIZED",
                "unauthorized",
                "Unauthorized",
                "Invalid credentials"
            ),
            Arguments.of(
                new RoutifyException.Forbidden("Insufficient permissions"),
                HttpStatus.FORBIDDEN,
                "FORBIDDEN",
                "forbidden",
                "Forbidden",
                "Insufficient permissions"
            ),
            Arguments.of(
                new RoutifyException.RateLimitExceeded(60),
                HttpStatus.TOO_MANY_REQUESTS,
                "RATE_LIMIT_EXCEEDED",
                "rate-limit-exceeded",
                "Rate Limit Exceeded",
                "Rate limit exceeded. Retry after 60 seconds"
            ),
            Arguments.of(
                new RoutifyException.QuotaExceeded("maxRoutes"),
                HttpStatus.PAYMENT_REQUIRED,
                "QUOTA_EXCEEDED",
                "quota-exceeded",
                "Quota Exceeded",
                "Tenant quota exceeded: maxRoutes"
            ),
            Arguments.of(
                new RoutifyException.GatewayError("route-service unreachable"),
                HttpStatus.BAD_GATEWAY,
                "GATEWAY_ERROR",
                "gateway-error",
                "Gateway Error",
                "route-service unreachable"
            ),
            Arguments.of(
                new RoutifyException.HeuristicError("Ambiguous route match"),
                HttpStatus.INTERNAL_SERVER_ERROR,
                "HEURISTIC_ERROR",
                "heuristic-error",
                "Heuristic Error",
                "Ambiguous route match"
            )
        );
    }

    @ParameterizedTest(name = "{2} → {1}")
    @MethodSource("allExceptionSubtypes")
    @DisplayName("RoutifyException subtype maps to correct ProblemDetail")
    void routifyExceptionMapping(
            RoutifyException exception,
            HttpStatus expectedStatus,
            String expectedErrorCode,
            String expectedTypeSuffix,
            String expectedTitle,
            String expectedDetail
    ) {
        ResponseEntity<ProblemDetail> response = handler.handleRoutifyException(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(expectedStatus);

        ProblemDetail problem = response.getBody();
        assertThat(problem).isNotNull();

        // HTTP status in the body
        assertThat(problem.getStatus()).isEqualTo(expectedStatus.value());

        // Detail message
        assertThat(problem.getDetail()).isEqualTo(expectedDetail);

        // RFC 9457 type URI
        assertThat(problem.getType()).isEqualTo(PROBLEM_BASE.resolve(expectedTypeSuffix));

        // Title (titleized from error code)
        assertThat(problem.getTitle()).isEqualTo(expectedTitle);

        // Custom properties
        assertThat(problem.getProperties()).containsEntry("errorCode", expectedErrorCode);
        assertThat(problem.getProperties()).containsKey("timestamp");
        assertThat(problem.getProperties()).containsEntry("path", "/api/v1/admin/routes");
    }

    // ─── Subtype-specific behaviour ───────────────────────────────────────────

    @Nested
    @DisplayName("Subtype-specific fields")
    class SubtypeSpecific {

        @Test
        @DisplayName("RateLimitExceeded includes retryAfter field")
        void rateLimitIncludesRetryAfter() {
            var ex = new RoutifyException.RateLimitExceeded(120);
            ResponseEntity<ProblemDetail> response = handler.handleRoutifyException(ex, request);

            ProblemDetail problem = response.getBody();
            assertThat(problem).isNotNull();
            assertThat(problem.getProperties()).containsEntry("retryAfter", 120L);
        }

        @Test
        @DisplayName("Non-RateLimitExceeded does NOT include retryAfter")
        void nonRateLimitHasNoRetryAfter() {
            var ex = new RoutifyException.NotFound("Route", "123");
            ResponseEntity<ProblemDetail> response = handler.handleRoutifyException(ex, request);

            ProblemDetail problem = response.getBody();
            assertThat(problem).isNotNull();
            assertThat(problem.getProperties()).doesNotContainKey("retryAfter");
        }

        @Test
        @DisplayName("GatewayError with cause preserves message")
        void gatewayErrorWithCause() {
            var cause = new RuntimeException("Connection refused");
            var ex = new RoutifyException.GatewayError("route-service down", cause);
            ResponseEntity<ProblemDetail> response = handler.handleRoutifyException(ex, request);

            ProblemDetail problem = response.getBody();
            assertThat(problem).isNotNull();
            assertThat(problem.getDetail()).isEqualTo("route-service down");
            assertThat(problem.getStatus()).isEqualTo(502);
        }
    }

    // ─── Generic exception handler ────────────────────────────────────────────

    @Nested
    @DisplayName("Generic exception handling")
    class GenericExceptions {

        @Test
        @DisplayName("Unhandled exception maps to 500 with safe message")
        void genericExceptionMapsTo500() {
            var ex = new RuntimeException("NullPointerException somewhere deep");
            ResponseEntity<ProblemDetail> response = handler.handleGeneric(ex, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

            ProblemDetail problem = response.getBody();
            assertThat(problem).isNotNull();
            assertThat(problem.getStatus()).isEqualTo(500);
            // Must NOT leak the internal exception message
            assertThat(problem.getDetail()).isEqualTo("An unexpected error occurred");
            assertThat(problem.getType()).isEqualTo(PROBLEM_BASE.resolve("internal-error"));
            assertThat(problem.getTitle()).isEqualTo("Internal Server Error");
            assertThat(problem.getProperties()).containsEntry("errorCode", "INTERNAL_ERROR");
            assertThat(problem.getProperties()).containsEntry("path", "/api/v1/admin/routes");
        }
    }

    // ─── RoutifyException hierarchy completeness ──────────────────────────────

    @Test
    @DisplayName("All sealed permits of RoutifyException are tested")
    void allPermitsTested() {
        var permits = RoutifyException.class.getPermittedSubclasses();
        assertThat(permits).hasSize(10);

        // Verify allExceptionSubtypes() covers all 10
        long testedCount = allExceptionSubtypes().count();
        assertThat(testedCount).isEqualTo(10);
    }
}

