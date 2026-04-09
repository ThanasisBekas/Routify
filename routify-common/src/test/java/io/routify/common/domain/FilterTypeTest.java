package io.routify.common.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link FilterType} enum — validates active/deprecated classification,
 * exhaustiveness, and naming conventions.
 *
 * <p>The primary goal is to catch accidental additions that forget to update the
 * {@code RouteDefinitionBuilder} switch expression in the gateway (which must
 * handle every enum value).
 */
@SuppressWarnings("deprecation")
class FilterTypeTest {

    /**
     * Set of all deprecated filter types as documented in AGENTS.md.
     * If a new @Deprecated value is added, this set must be updated.
     */
    private static final Set<FilterType> DEPRECATED_TYPES = Set.of(
            FilterType.AUTH_NONE,
            FilterType.RATE_LIMIT_TOKEN_BUCKET,
            FilterType.PATH_REWRITE,
            FilterType.PATH_STRIP_PREFIX,
            FilterType.PATH_ADD_PREFIX,
            FilterType.QUERY_PARAM_MODIFY,
            FilterType.BODY_JSONATA_TRANSFORM,
            FilterType.BODY_SPEL_TRANSFORM,
            FilterType.VALIDATE_REGEX,
            FilterType.VALIDATE_SIZE,
            FilterType.CIRCUIT_BREAKER,
            FilterType.RETRY
    );

    // ─── Exhaustiveness ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Enum exhaustiveness")
    class Exhaustiveness {

        @Test
        @DisplayName("Exactly 12 deprecated filter types exist")
        void deprecatedCount() {
            long deprecatedCount = Arrays.stream(FilterType.values())
                    .filter(ft -> {
                        try {
                            return FilterType.class.getField(ft.name()).isAnnotationPresent(Deprecated.class);
                        } catch (NoSuchFieldException e) {
                            return false;
                        }
                    })
                    .count();

            assertThat(deprecatedCount).isEqualTo(12);
        }

        @Test
        @DisplayName("Total enum values = active + deprecated")
        void totalEqualsActivesPlusDeprecated() {
            int total = FilterType.values().length;

            long activeCount = Arrays.stream(FilterType.values())
                    .filter(ft -> !DEPRECATED_TYPES.contains(ft))
                    .count();

            assertThat(activeCount + DEPRECATED_TYPES.size()).isEqualTo(total);
        }

        @Test
        @DisplayName("Every deprecated type has @Deprecated annotation")
        void deprecatedTypesAnnotated() {
            for (FilterType ft : DEPRECATED_TYPES) {
                try {
                    assertThat(FilterType.class.getField(ft.name()).isAnnotationPresent(Deprecated.class))
                            .as("%s should have @Deprecated", ft)
                            .isTrue();
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Field not found: " + ft.name(), e);
                }
            }
        }
    }

    // ─── Active filter types ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Active filter types")
    class ActiveTypes {

        @Test
        @DisplayName("All 29 active filter types from AGENTS.md exist")
        void activeTypesExist() {
            Set<String> expectedActive = Set.of(
                    "AUTH_API_KEY", "AUTH_BASIC", "AUTH_JWT", "AUTH_MTLS", "AUTH_OAUTH2",
                    "AUTH_CLIENT_ID", "AUTH_CERT_VAULT",
                    "DOWNSTREAM_BASIC_AUTH", "DOWNSTREAM_BEARER_CC", "OAUTH2_TOKEN_RELAY",
                    "RATE_LIMIT_FIXED_WINDOW", "RATE_LIMIT_SLIDING_WINDOW",
                    "REQUEST_HEADER_MODIFY", "RESPONSE_HEADER_MODIFY", "RESPONSE_HEADER_REWRITE",
                    "BODY_JOLT_TRANSFORM",
                    "VALIDATE_JSON_SCHEMA", "REQUEST_SIZE_LIMIT", "GRAPHQL_DEPTH_LIMIT",
                    "RESPONSE_CACHE", "REQUEST_DECOMPRESS",
                    "IDEMPOTENCY_KEY",
                    "TIMEOUT", "CIRCUIT_BREAKER_V2", "RETRY_V2",
                    "CONDITIONAL_ROUTE", "USER_ID_PAYLOAD_ROUTING", "GEO_ROUTE",
                    "IP_ACCESS_CONTROL",
                    "CERT_ROTATION", "CERT_VAULT_EXPIRY_CHECK",
                    "API_VERSIONING",
                    "CORRELATION_ID", "REQUEST_LOGGER", "TENANT_CONTEXT",
                    "SECURITY_HEADERS", "CUSTOM_METRIC", "BODY_SIZE_METRIC",
                    "WEBHOOK_NOTIFY",
                    "MOCK_RESPONSE",
                    "CUSTOM_SPEL",
                    "AI_FILTER", "AI_MODIFIER"
            );

            Set<String> actualActive = Arrays.stream(FilterType.values())
                    .filter(ft -> !DEPRECATED_TYPES.contains(ft))
                    .map(Enum::name)
                    .collect(Collectors.toSet());

            assertThat(actualActive).containsAll(expectedActive);
        }

        @ParameterizedTest
        @EnumSource(value = FilterType.class, names = {
                "AUTH_API_KEY", "AUTH_BASIC", "AUTH_JWT", "AUTH_MTLS",
                "AUTH_OAUTH2", "AUTH_CLIENT_ID", "AUTH_CERT_VAULT"
        })
        @DisplayName("Authentication filter types start with AUTH_")
        void authTypesStartWithAuth(FilterType type) {
            assertThat(type.name()).startsWith("AUTH_");
        }

        @ParameterizedTest
        @EnumSource(value = FilterType.class, names = {
                "RATE_LIMIT_FIXED_WINDOW", "RATE_LIMIT_SLIDING_WINDOW"
        })
        @DisplayName("Rate limit types start with RATE_LIMIT_")
        void rateLimitTypesStartWithRateLimit(FilterType type) {
            assertThat(type.name()).startsWith("RATE_LIMIT_");
        }
    }

    // ─── Naming conventions ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Naming conventions")
    class NamingConventions {

        @ParameterizedTest
        @EnumSource(FilterType.class)
        @DisplayName("All filter type names are UPPER_SNAKE_CASE")
        void allNamesUpperSnakeCase(FilterType type) {
            assertThat(type.name()).matches("[A-Z][A-Z0-9_]*");
        }

        @ParameterizedTest
        @EnumSource(FilterType.class)
        @DisplayName("No filter type name contains consecutive underscores")
        void noConsecutiveUnderscores(FilterType type) {
            assertThat(type.name()).doesNotContain("__");
        }
    }

    // ─── valueOf round-trip ───────────────────────────────────────────────────

    @Nested
    @DisplayName("valueOf round-trip")
    class ValueOfRoundTrip {

        @ParameterizedTest
        @EnumSource(FilterType.class)
        @DisplayName("valueOf(name()) returns the same enum constant")
        void valueOfRoundTrip(FilterType type) {
            assertThat(FilterType.valueOf(type.name())).isEqualTo(type);
        }
    }

    // ─── DEPRECATED set and isDeprecated() ──────────────────────────────────

    @Nested
    @DisplayName("DEPRECATED set and isDeprecated()")
    class DeprecatedSetTests {

        @Test
        @DisplayName("DEPRECATED set matches the test-local DEPRECATED_TYPES set")
        void deprecatedSetMatchesTestSet() {
            assertThat(FilterType.DEPRECATED).containsExactlyInAnyOrderElementsOf(DEPRECATED_TYPES);
        }

        @Test
        @DisplayName("DEPRECATED set has exactly 12 entries")
        void deprecatedSetSize() {
            assertThat(FilterType.DEPRECATED).hasSize(12);
        }

        @ParameterizedTest
        @EnumSource(value = FilterType.class, names = {
                "AUTH_NONE", "RATE_LIMIT_TOKEN_BUCKET", "PATH_REWRITE", "PATH_STRIP_PREFIX",
                "PATH_ADD_PREFIX", "QUERY_PARAM_MODIFY", "BODY_JSONATA_TRANSFORM",
                "BODY_SPEL_TRANSFORM", "VALIDATE_REGEX", "VALIDATE_SIZE",
                "CIRCUIT_BREAKER", "RETRY"
        })
        @DisplayName("isDeprecated() returns true for deprecated types")
        void isDeprecatedTrue(FilterType type) {
            assertThat(type.isDeprecated()).isTrue();
        }

        @ParameterizedTest
        @EnumSource(value = FilterType.class, names = {
                "AUTH_JWT", "AUTH_API_KEY", "RATE_LIMIT_FIXED_WINDOW", "CIRCUIT_BREAKER_V2",
                "RETRY_V2", "AI_FILTER", "AI_MODIFIER", "CUSTOM_SPEL"
        })
        @DisplayName("isDeprecated() returns false for active types")
        void isDeprecatedFalse(FilterType type) {
            assertThat(type.isDeprecated()).isFalse();
        }

        @Test
        @DisplayName("DEPRECATED set is unmodifiable")
        void deprecatedSetIsUnmodifiable() {
            org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                    () -> FilterType.DEPRECATED.add(FilterType.AUTH_JWT));
        }
    }
}

