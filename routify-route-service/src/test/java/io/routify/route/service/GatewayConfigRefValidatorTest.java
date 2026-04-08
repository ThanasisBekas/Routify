package io.routify.route.service;

import io.routify.common.exception.RoutifyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GatewayConfigRefValidator}.
 *
 * <p>Validates the eagerly-checked structural rules (unknown refType, blank refId)
 * and the warning-only soft checks (entry not found in config).
 */
class GatewayConfigRefValidatorTest {

    private GatewayConfigService configService;
    private GatewayConfigRefValidator validator;

    @BeforeEach
    void setUp() {
        configService = mock(GatewayConfigService.class);
        validator = new GatewayConfigRefValidator(configService);
    }

    // ─── Null / empty ref → no-op ─────────────────────────────────────────────

    @Test
    @DisplayName("Null gatewayConfigRef — no validation, no exception")
    void nullRef_noException() {
        assertThatCode(() -> validator.validate(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Empty gatewayConfigRef map — no validation, no exception")
    void emptyRef_noException() {
        assertThatCode(() -> validator.validate(Map.of())).doesNotThrowAnyException();
    }

    // ─── Unknown refType → reject ─────────────────────────────────────────────

    @Test
    @DisplayName("Unknown refType throws Validation exception")
    void unknownRefType_throwsValidation() {
        Map<String, Object> ref = Map.of("refType", "TOTALLY_UNKNOWN", "refId", "abc");

        assertThatThrownBy(() -> validator.validate(ref))
                .isInstanceOf(RoutifyException.Validation.class)
                .hasMessageContaining("Unknown gatewayConfigRef refType")
                .hasMessageContaining("TOTALLY_UNKNOWN");
    }

    @Test
    @DisplayName("Missing refType throws Validation exception")
    void missingRefType_throwsValidation() {
        Map<String, Object> ref = Map.of("refId", "abc");

        assertThatThrownBy(() -> validator.validate(ref))
                .isInstanceOf(RoutifyException.Validation.class)
                .hasMessageContaining("missing required 'refType' field");
    }

    @Test
    @DisplayName("Blank refType throws Validation exception")
    void blankRefType_throwsValidation() {
        Map<String, Object> ref = Map.of("refType", "  ", "refId", "abc");

        assertThatThrownBy(() -> validator.validate(ref))
                .isInstanceOf(RoutifyException.Validation.class)
                .hasMessageContaining("missing required 'refType' field");
    }

    // ─── All known refTypes are accepted ──────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "AUTH_PROVIDER", "RATE_LIMIT_POLICY", "CIRCUIT_BREAKER_DEFAULTS",
            "RESILIENCE_DEFAULTS", "DOWNSTREAM_CREDENTIAL",
            "MTLS_CLIENT_MAPPING", "CLIENT_ID_MAPPING"
    })
    @DisplayName("Known refType with valid refId and empty config — accepted (warning-only)")
    void knownRefType_emptyConfig_doesNotThrow(String refType) {
        when(configService.getGlobalConfig()).thenReturn(Map.of());
        Map<String, Object> ref = Map.of("refType", refType, "refId", "some-id");

        assertThatCode(() -> validator.validate(ref)).doesNotThrowAnyException();
    }

    // ─── VAULT_CERT ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("VAULT_CERT with non-blank refId — accepted (no config lookup)")
    void vaultCert_validRefId_accepted() {
        Map<String, Object> ref = Map.of("refType", "VAULT_CERT", "refId", "my-logical-id");

        assertThatCode(() -> validator.validate(ref)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("VAULT_CERT with blank refId — throws Validation")
    void vaultCert_blankRefId_throwsValidation() {
        Map<String, Object> ref = Map.of("refType", "VAULT_CERT", "refId", "  ");

        assertThatThrownBy(() -> validator.validate(ref))
                .isInstanceOf(RoutifyException.Validation.class)
                .hasMessageContaining("VAULT_CERT")
                .hasMessageContaining("non-blank 'refId'");
    }

    @Test
    @DisplayName("VAULT_CERT with null refId — throws Validation")
    void vaultCert_nullRefId_throwsValidation() {
        Map<String, Object> ref = new HashMap<>();
        ref.put("refType", "VAULT_CERT");
        ref.put("refId", null);

        assertThatThrownBy(() -> validator.validate(ref))
                .isInstanceOf(RoutifyException.Validation.class)
                .hasMessageContaining("VAULT_CERT");
    }

    // ─── List-based types: blank refId → reject ───────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "AUTH_PROVIDER", "RATE_LIMIT_POLICY", "DOWNSTREAM_CREDENTIAL",
            "MTLS_CLIENT_MAPPING", "CLIENT_ID_MAPPING"
    })
    @DisplayName("List-based refType with blank refId — throws Validation")
    void listRefType_blankRefId_throwsValidation(String refType) {
        Map<String, Object> ref = Map.of("refType", refType, "refId", "");

        assertThatThrownBy(() -> validator.validate(ref))
                .isInstanceOf(RoutifyException.Validation.class)
                .hasMessageContaining(refType)
                .hasMessageContaining("non-blank 'refId'");
    }

    // ─── AUTH_PROVIDER: entry found in config → accepted ──────────────────────

    @Test
    @DisplayName("AUTH_PROVIDER with matching refId in config — accepted silently")
    void authProvider_matching_accepted() {
        Map<String, Object> config = Map.of(
                "authProviders", List.of(
                        Map.of("id", "ap-1", "name", "My Provider", "type", "BASIC"),
                        Map.of("id", "ap-2", "name", "JWT Provider", "type", "JWT_VERIFY")
                )
        );
        when(configService.getGlobalConfig()).thenReturn(config);

        Map<String, Object> ref = Map.of("refType", "AUTH_PROVIDER", "refId", "ap-2");

        assertThatCode(() -> validator.validate(ref)).doesNotThrowAnyException();
    }

    // ─── AUTH_PROVIDER: entry not found → accepted with warning ───────────────

    @Test
    @DisplayName("AUTH_PROVIDER with non-matching refId — accepted (warning only)")
    void authProvider_notMatching_acceptedWithWarning() {
        Map<String, Object> config = Map.of(
                "authProviders", List.of(
                        Map.of("id", "ap-1", "name", "My Provider", "type", "BASIC")
                )
        );
        when(configService.getGlobalConfig()).thenReturn(config);

        Map<String, Object> ref = Map.of("refType", "AUTH_PROVIDER", "refId", "non-existent");

        // Warning logged, but no exception — entry may be created later
        assertThatCode(() -> validator.validate(ref)).doesNotThrowAnyException();
    }

    // ─── RATE_LIMIT_POLICY: entry found ───────────────────────────────────────

    @Test
    @DisplayName("RATE_LIMIT_POLICY with matching refId — accepted")
    void rateLimitPolicy_matching_accepted() {
        Map<String, Object> config = Map.of(
                "rateLimitPolicies", List.of(
                        Map.of("id", "rl-1", "name", "Default Policy")
                )
        );
        when(configService.getGlobalConfig()).thenReturn(config);

        Map<String, Object> ref = Map.of("refType", "RATE_LIMIT_POLICY", "refId", "rl-1");

        assertThatCode(() -> validator.validate(ref)).doesNotThrowAnyException();
    }

    // ─── DOWNSTREAM_CREDENTIAL: entry found ───────────────────────────────────

    @Test
    @DisplayName("DOWNSTREAM_CREDENTIAL with matching refId — accepted")
    void downstreamCredential_matching_accepted() {
        Map<String, Object> config = Map.of(
                "downstreamCredentials", List.of(
                        Map.of("id", "dc-1", "name", "Service Account", "type", "BASIC")
                )
        );
        when(configService.getGlobalConfig()).thenReturn(config);

        Map<String, Object> ref = Map.of("refType", "DOWNSTREAM_CREDENTIAL", "refId", "dc-1");

        assertThatCode(() -> validator.validate(ref)).doesNotThrowAnyException();
    }

    // ─── Singleton types ──────────────────────────────────────────────────────

    @Test
    @DisplayName("CIRCUIT_BREAKER_DEFAULTS with section present — accepted")
    void circuitBreakerDefaults_sectionPresent_accepted() {
        Map<String, Object> config = Map.of(
                "circuitBreakerDefaults", Map.of("slidingWindowSize", 10)
        );
        when(configService.getGlobalConfig()).thenReturn(config);

        Map<String, Object> ref = Map.of("refType", "CIRCUIT_BREAKER_DEFAULTS", "refId", "any");

        assertThatCode(() -> validator.validate(ref)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("RESILIENCE_DEFAULTS with section missing — accepted (warning only)")
    void resilienceDefaults_sectionMissing_acceptedWithWarning() {
        when(configService.getGlobalConfig()).thenReturn(Map.of());

        Map<String, Object> ref = Map.of("refType", "RESILIENCE_DEFAULTS", "refId", "any");

        // Warning logged, but no exception — config may be created later
        assertThatCode(() -> validator.validate(ref)).doesNotThrowAnyException();
    }

    // ─── Edge cases ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Config section exists but is not a list — accepted (warning only)")
    void configSectionNotAList_acceptedWithWarning() {
        Map<String, Object> config = Map.of("authProviders", "not-a-list");
        when(configService.getGlobalConfig()).thenReturn(config);

        Map<String, Object> ref = Map.of("refType", "AUTH_PROVIDER", "refId", "ap-1");

        assertThatCode(() -> validator.validate(ref)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Config section is null — accepted (warning only)")
    void configSectionNull_acceptedWithWarning() {
        Map<String, Object> config = new HashMap<>();
        config.put("authProviders", null);
        when(configService.getGlobalConfig()).thenReturn(config);

        Map<String, Object> ref = Map.of("refType", "AUTH_PROVIDER", "refId", "ap-1");

        assertThatCode(() -> validator.validate(ref)).doesNotThrowAnyException();
    }
}

