package io.routify.admin.service;

import io.routify.common.domain.FilterType;
import io.routify.common.exception.RoutifyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link FilterConfigValidator}.
 *
 * <p>Verifies that required config fields are enforced for each filter type
 * and that valid configs pass without exception.
 */
class FilterConfigValidatorTest {

    private FilterConfigValidator validator;

    @BeforeEach
    void setUp() {
        validator = new FilterConfigValidator();
    }

    // ─── Null/empty config ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Null and empty configs")
    class NullAndEmptyConfigs {

        @Test
        @DisplayName("Null config is treated as empty map — passes for types with no required fields")
        void nullConfigPassesForOptionalTypes() {
            assertThatCode(() -> validator.validate(FilterType.CORRELATION_ID, null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Empty config passes for types with no required fields")
        void emptyConfigPassesForOptionalTypes() {
            assertThatCode(() -> validator.validate(FilterType.SECURITY_HEADERS, Map.of()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Null config fails for types with required fields")
        void nullConfigFailsForRequiredFields() {
            assertThatThrownBy(() -> validator.validate(FilterType.DOWNSTREAM_BASIC_AUTH, null))
                    .isInstanceOf(RoutifyException.Validation.class)
                    .hasMessageContaining("username")
                    .hasMessageContaining("password");
        }
    }

    // ─── Authentication filters ────────────────────────────────────────────────

    @Nested
    @DisplayName("Authentication filters")
    class AuthFilters {

        @Test
        @DisplayName("AUTH_BASIC fails with empty config and no gatewayConfigRef")
        void authBasicFailsWithoutCredentialsOrProvider() {
            assertThatThrownBy(() -> validator.validate(FilterType.AUTH_BASIC, Map.of()))
                    .isInstanceOf(RoutifyException.Validation.class)
                    .hasMessageContaining("AUTH_BASIC");
        }

        @Test
        @DisplayName("AUTH_BASIC fails with null config and no gatewayConfigRef")
        void authBasicFailsWithNullConfigAndNoProvider() {
            assertThatThrownBy(() -> validator.validate(FilterType.AUTH_BASIC, null, null))
                    .isInstanceOf(RoutifyException.Validation.class)
                    .hasMessageContaining("AUTH_BASIC");
        }

        @Test
        @DisplayName("AUTH_BASIC passes with empty config when gatewayConfigRef is provided")
        void authBasicPassesWithProvider() {
            Map<String, Object> ref = Map.of("refType", "AUTH_PROVIDER", "refId", "ap-123");
            assertThatCode(() -> validator.validate(FilterType.AUTH_BASIC, Map.of(), ref))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("AUTH_BASIC passes with explicit credentials in config (no provider needed)")
        void authBasicPassesWithCredentials() {
            assertThatCode(() -> validator.validate(FilterType.AUTH_BASIC,
                    Map.of("username", "admin", "password", "secret")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("AUTH_BASIC passes with explicit credentials even when gatewayConfigRef is also present")
        void authBasicPassesWithCredentialsAndProvider() {
            Map<String, Object> ref = Map.of("refType", "AUTH_PROVIDER", "refId", "ap-123");
            assertThatCode(() -> validator.validate(FilterType.AUTH_BASIC,
                    Map.of("username", "admin", "password", "secret"), ref))
                    .doesNotThrowAnyException();
        }


        @Test
        @DisplayName("AUTH_JWT passes with empty config (all optional)")
        void authJwtOptional() {
            assertThatCode(() -> validator.validate(FilterType.AUTH_JWT, Map.of()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("AUTH_API_KEY passes with empty config (headerName defaults)")
        void authApiKeyOptional() {
            assertThatCode(() -> validator.validate(FilterType.AUTH_API_KEY, Map.of()))
                    .doesNotThrowAnyException();
        }
    }

    // ─── Downstream Auth ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Downstream auth filters")
    class DownstreamAuthFilters {

        @Test
        @DisplayName("DOWNSTREAM_BASIC_AUTH requires username and password")
        void downstreamBasicAuthRequiresFields() {
            assertThatThrownBy(() -> validator.validate(FilterType.DOWNSTREAM_BASIC_AUTH, Map.of()))
                    .isInstanceOf(RoutifyException.Validation.class)
                    .hasMessageContaining("username")
                    .hasMessageContaining("password");
        }

        @Test
        @DisplayName("OAUTH2_TOKEN_RELAY requires tokenEndpoint, clientId, clientSecret")
        void oauth2TokenRelayRequiresFields() {
            assertThatThrownBy(() -> validator.validate(FilterType.OAUTH2_TOKEN_RELAY, Map.of()))
                    .isInstanceOf(RoutifyException.Validation.class)
                    .hasMessageContaining("tokenEndpoint")
                    .hasMessageContaining("clientId")
                    .hasMessageContaining("clientSecret");
        }

        @Test
        @DisplayName("OAUTH2_TOKEN_RELAY passes with valid config")
        void oauth2TokenRelayValid() {
            assertThatCode(() -> validator.validate(FilterType.OAUTH2_TOKEN_RELAY,
                    Map.of("tokenEndpoint", "https://auth.example.com/token",
                            "clientId", "my-client",
                            "clientSecret", "my-secret")))
                    .doesNotThrowAnyException();
        }
    }

    // ─── Rate Limiting ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Rate limiting filters")
    class RateLimitFilters {

        @Test
        @DisplayName("RATE_LIMIT_FIXED_WINDOW requires maxRequests and windowMs")
        void fixedWindowRequiresFields() {
            assertThatThrownBy(() -> validator.validate(FilterType.RATE_LIMIT_FIXED_WINDOW, Map.of()))
                    .isInstanceOf(RoutifyException.Validation.class)
                    .hasMessageContaining("maxRequests")
                    .hasMessageContaining("windowMs");
        }

        @Test
        @DisplayName("RATE_LIMIT_FIXED_WINDOW passes with valid config")
        void fixedWindowValid() {
            assertThatCode(() -> validator.validate(FilterType.RATE_LIMIT_FIXED_WINDOW,
                    Map.of("maxRequests", 100, "windowMs", 60000)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("RATE_LIMIT_FIXED_WINDOW rejects non-positive maxRequests")
        void fixedWindowNonPositive() {
            assertThatThrownBy(() -> validator.validate(FilterType.RATE_LIMIT_FIXED_WINDOW,
                    Map.of("maxRequests", 0, "windowMs", 60000)))
                    .isInstanceOf(RoutifyException.Validation.class)
                    .hasMessageContaining("maxRequests");
        }

        @Test
        @DisplayName("RATE_LIMIT_FIXED_WINDOW rejects non-numeric maxRequests")
        void fixedWindowNonNumeric() {
            assertThatThrownBy(() -> validator.validate(FilterType.RATE_LIMIT_FIXED_WINDOW,
                    Map.of("maxRequests", "banana", "windowMs", 60000)))
                    .isInstanceOf(RoutifyException.Validation.class)
                    .hasMessageContaining("maxRequests");
        }

        @Test
        @DisplayName("RATE_LIMIT_SLIDING_WINDOW has same requirements")
        void slidingWindowRequiresFields() {
            assertThatThrownBy(() -> validator.validate(FilterType.RATE_LIMIT_SLIDING_WINDOW, Map.of()))
                    .isInstanceOf(RoutifyException.Validation.class)
                    .hasMessageContaining("maxRequests")
                    .hasMessageContaining("windowMs");
        }
    }

    // ─── Response Header Rewrite ────────────────────────────────────────────────

    @Nested
    @DisplayName("RESPONSE_HEADER_REWRITE")
    class ResponseHeaderRewrite {

        @Test
        @DisplayName("Requires headerName, pattern, and replacement")
        void requiresAllFields() {
            assertThatThrownBy(() -> validator.validate(FilterType.RESPONSE_HEADER_REWRITE, Map.of()))
                    .isInstanceOf(RoutifyException.Validation.class)
                    .hasMessageContaining("headerName")
                    .hasMessageContaining("pattern")
                    .hasMessageContaining("replacement");
        }

        @Test
        @DisplayName("Passes with valid config")
        void valid() {
            assertThatCode(() -> validator.validate(FilterType.RESPONSE_HEADER_REWRITE,
                    Map.of("headerName", "Location",
                            "pattern", "http://internal",
                            "replacement", "https://external")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Passes with empty replacement string (valid use case)")
        void emptyReplacementValid() {
            var config = new HashMap<String, Object>();
            config.put("headerName", "Location");
            config.put("pattern", "http://internal");
            config.put("replacement", "");
            assertThatCode(() -> validator.validate(FilterType.RESPONSE_HEADER_REWRITE, config))
                    .doesNotThrowAnyException();
        }
    }

    // ─── Certificate filters ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Certificate filters")
    class CertFilters {

        @Test
        @DisplayName("CERT_ROTATION requires logicalId")
        void certRotationRequiresLogicalId() {
            assertThatThrownBy(() -> validator.validate(FilterType.CERT_ROTATION, Map.of()))
                    .isInstanceOf(RoutifyException.Validation.class)
                    .hasMessageContaining("logicalId");
        }

        @Test
        @DisplayName("CERT_ROTATION passes with logicalId")
        void certRotationValid() {
            assertThatCode(() -> validator.validate(FilterType.CERT_ROTATION,
                    Map.of("logicalId", "my-cert-id")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("CERT_VAULT_EXPIRY_CHECK requires logicalId")
        void certVaultExpiryCheckRequiresLogicalId() {
            assertThatThrownBy(() -> validator.validate(FilterType.CERT_VAULT_EXPIRY_CHECK, Map.of()))
                    .isInstanceOf(RoutifyException.Validation.class)
                    .hasMessageContaining("logicalId");
        }
    }

    // ─── AI filters ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("AI filters")
    class AiFilters {

        @Test
        @DisplayName("AI_FILTER requires policyDescription")
        void aiFilterRequiresPolicyDescription() {
            assertThatThrownBy(() -> validator.validate(FilterType.AI_FILTER, Map.of()))
                    .isInstanceOf(RoutifyException.Validation.class)
                    .hasMessageContaining("policyDescription");
        }

        @Test
        @DisplayName("AI_MODIFIER requires modificationPrompt")
        void aiModifierRequiresModificationPrompt() {
            assertThatThrownBy(() -> validator.validate(FilterType.AI_MODIFIER, Map.of()))
                    .isInstanceOf(RoutifyException.Validation.class)
                    .hasMessageContaining("modificationPrompt");
        }

        @Test
        @DisplayName("AI_FILTER passes with valid config")
        void aiFilterValid() {
            assertThatCode(() -> validator.validate(FilterType.AI_FILTER,
                    Map.of("policyDescription", "Block requests with SQL injection")))
                    .doesNotThrowAnyException();
        }
    }

    // ─── Miscellaneous filters ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Miscellaneous filters")
    class MiscFilters {

        @Test
        @DisplayName("CUSTOM_SPEL requires expression")
        void customSpelRequiresExpression() {
            assertThatThrownBy(() -> validator.validate(FilterType.CUSTOM_SPEL, Map.of()))
                    .isInstanceOf(RoutifyException.Validation.class)
                    .hasMessageContaining("expression");
        }

        @Test
        @DisplayName("CUSTOM_METRIC requires metricName")
        void customMetricRequiresMetricName() {
            assertThatThrownBy(() -> validator.validate(FilterType.CUSTOM_METRIC, Map.of()))
                    .isInstanceOf(RoutifyException.Validation.class)
                    .hasMessageContaining("metricName");
        }

        @Test
        @DisplayName("WEBHOOK_NOTIFY requires webhookUrl")
        void webhookNotifyRequiresUrl() {
            assertThatThrownBy(() -> validator.validate(FilterType.WEBHOOK_NOTIFY, Map.of()))
                    .isInstanceOf(RoutifyException.Validation.class)
                    .hasMessageContaining("webhookUrl");
        }

        @Test
        @DisplayName("BODY_JOLT_TRANSFORM requires spec")
        void joltTransformRequiresSpec() {
            assertThatThrownBy(() -> validator.validate(FilterType.BODY_JOLT_TRANSFORM, Map.of()))
                    .isInstanceOf(RoutifyException.Validation.class)
                    .hasMessageContaining("spec");
        }

        @Test
        @DisplayName("BODY_JOLT_TRANSFORM rejects empty array spec '[]'")
        void joltTransformRejectsEmptyArraySpec() {
            assertThatThrownBy(() -> validator.validate(FilterType.BODY_JOLT_TRANSFORM, Map.of("spec", "[]")))
                    .isInstanceOf(RoutifyException.Validation.class)
                    .hasMessageContaining("spec")
                    .hasMessageContaining("empty spec array");
        }

        @Test
        @DisplayName("BODY_JOLT_TRANSFORM BOTH phase requires responseSpec")
        void joltTransformBothPhaseRequiresResponseSpec() {
            assertThatThrownBy(() -> validator.validate(FilterType.BODY_JOLT_TRANSFORM,
                    Map.of("spec", "[{\"operation\":\"shift\",\"spec\":{\"id\":\"userId\"}}]", "phase", "BOTH")))
                    .isInstanceOf(RoutifyException.Validation.class)
                    .hasMessageContaining("responseSpec");
        }

        @Test
        @DisplayName("VALIDATE_JSON_SCHEMA requires schema")
        void jsonSchemaRequiresSchema() {
            assertThatThrownBy(() -> validator.validate(FilterType.VALIDATE_JSON_SCHEMA, Map.of()))
                    .isInstanceOf(RoutifyException.Validation.class)
                    .hasMessageContaining("schema");
        }

        @Test
        @DisplayName("TIMEOUT requires timeoutMs")
        void timeoutRequiresTimeoutMs() {
            assertThatThrownBy(() -> validator.validate(FilterType.TIMEOUT, Map.of()))
                    .isInstanceOf(RoutifyException.Validation.class)
                    .hasMessageContaining("timeoutMs");
        }

        @Test
        @DisplayName("IP_ACCESS_CONTROL requires addresses")
        void ipAccessControlRequiresAddresses() {
            assertThatThrownBy(() -> validator.validate(FilterType.IP_ACCESS_CONTROL, Map.of()))
                    .isInstanceOf(RoutifyException.Validation.class)
                    .hasMessageContaining("addresses");
        }
    }

    // ─── Types with all-optional configs ────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(value = FilterType.class, names = {
            "CORRELATION_ID", "TENANT_CONTEXT", "SECURITY_HEADERS",
            "REQUEST_HEADER_MODIFY", "RESPONSE_HEADER_MODIFY",
            "GRAPHQL_DEPTH_LIMIT", "RESPONSE_CACHE", "REQUEST_DECOMPRESS",
            "IDEMPOTENCY_KEY", "CIRCUIT_BREAKER_V2", "RETRY_V2",
            "MOCK_RESPONSE", "BODY_SIZE_METRIC", "GEO_ROUTE", "API_VERSIONING",
            "AUTH_JWT", "AUTH_API_KEY", "AUTH_OAUTH2", "AUTH_MTLS", "AUTH_CLIENT_ID",
            "AUTH_CERT_VAULT", "DOWNSTREAM_BEARER_CC", "REQUEST_LOGGER"
    })
    @DisplayName("Types with all-optional configs pass with empty config")
    void optionalConfigTypes(FilterType type) {
        assertThatCode(() -> validator.validate(type, Map.of()))
                .doesNotThrowAnyException();
    }

    // ─── Error message formatting ───────────────────────────────────────────────

    @Test
    @DisplayName("Error message includes filter type name")
    void errorMessageIncludesFilterType() {
        assertThatThrownBy(() -> validator.validate(FilterType.DOWNSTREAM_BASIC_AUTH, Map.of()))
                .isInstanceOf(RoutifyException.Validation.class)
                .hasMessageContaining("DOWNSTREAM_BASIC_AUTH");
    }

    @Test
    @DisplayName("Multiple missing fields are all listed in error")
    void multipleErrors() {
        var exception = org.junit.jupiter.api.Assertions.assertThrows(
                RoutifyException.Validation.class,
                () -> validator.validate(FilterType.OAUTH2_TOKEN_RELAY, Map.of())
        );
        assertThatThrownBy(() -> { throw exception; })
                .hasMessageContaining("tokenEndpoint")
                .hasMessageContaining("clientId")
                .hasMessageContaining("clientSecret");
    }

    // ─── Type checking ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("String field rejects non-string value")
    void stringFieldRejectsNonString() {
        assertThatThrownBy(() -> validator.validate(FilterType.CERT_ROTATION,
                Map.of("logicalId", 12345)))
                .isInstanceOf(RoutifyException.Validation.class)
                .hasMessageContaining("logicalId")
                .hasMessageContaining("string");
    }

    @Test
    @DisplayName("Number field accepts string representation of number")
    void numberFieldAcceptsStringNumber() {
        assertThatCode(() -> validator.validate(FilterType.TIMEOUT,
                Map.of("timeoutMs", "30000")))
                .doesNotThrowAnyException();
    }
}

