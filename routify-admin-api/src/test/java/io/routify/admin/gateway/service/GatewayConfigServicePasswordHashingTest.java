package io.routify.admin.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.routify.admin.gateway.dto.GatewayConfigDto;
import io.routify.admin.gateway.dto.GatewayConfigDto.AuthProviderDto;
import io.routify.common.web.Sensitive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P-03 BasicAuth Password Hashing — unit tests for the BCrypt hashing
 * logic in {@link GatewayConfigService}.
 *
 * <p>Covers:
 * <ul>
 *   <li>BASIC auth provider passwords are BCrypt-hashed on upsert</li>
 *   <li>Already-hashed passwords ($2 prefix) are NOT re-hashed</li>
 *   <li>Masked passwords ([REDACTED]) are preserved from existing config</li>
 *   <li>Null/blank passwords are left untouched</li>
 *   <li>Non-BASIC auth providers are not hashed</li>
 *   <li>saveConfig() hashes all BASIC providers in the full config</li>
 * </ul>
 */
class GatewayConfigServicePasswordHashingTest {

    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder(12);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private StringRedisTemplate redisTemplate;
    private GatewayActuatorClient gatewayActuatorClient;
    private RouteServiceConfigClient routeServiceConfigClient;
    private GatewayConfigService service;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        gatewayActuatorClient = mock(GatewayActuatorClient.class);
        routeServiceConfigClient = mock(RouteServiceConfigClient.class);

        // Stub Redis operations
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        // By default, Redis returns null (cache miss) → fall back to DB
        when(valueOps.get(GatewayConfigService.CACHE_KEY)).thenReturn(null);

        // DB returns a default config with no auth providers
        when(routeServiceConfigClient.fetchConfig()).thenReturn(
                MAPPER.convertValue(buildMinimalConfig(), Map.class));

        // saveConfig succeeds — returns the same map back
        when(routeServiceConfigClient.saveConfig(any(), anyString(), anyString()))
                .thenReturn(Map.of());

        service = new GatewayConfigService(redisTemplate, MAPPER, gatewayActuatorClient, routeServiceConfigClient);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildMinimalConfig() {
        GatewayConfigDto dto = GatewayConfigDto.builder()
                .authProviders(new ArrayList<>())
                .build();
        return MAPPER.convertValue(dto, Map.class);
    }

    private AuthProviderDto basicProvider(String id, String password) {
        return AuthProviderDto.builder()
                .id(id).name("Test Basic").type("BASIC")
                .username("admin").password(password).enabled(true)
                .build();
    }

    private AuthProviderDto oauth2Provider(String id, String secret) {
        return AuthProviderDto.builder()
                .id(id).name("Test OAuth2").type("OAUTH2_CLIENT_CREDENTIALS")
                .clientId("client-id").clientSecret(secret).enabled(true)
                .build();
    }

    // ─── upsertAuthProvider — BASIC hashing ───────────────────────────────────

    @Test
    @DisplayName("upsertAuthProvider — BASIC provider with plain-text password → BCrypt-hashed")
    void upsertBasicProvider_plainTextPassword_isHashed() {
        AuthProviderDto provider = basicProvider("bp-1", "myPlainPassword");

        service.upsertAuthProvider(provider, "test-user");

        // The password should now be a BCrypt hash
        assertThat(provider.getPassword()).startsWith("$2");
        assertThat(BCRYPT.matches("myPlainPassword", provider.getPassword())).isTrue();
    }

    @Test
    @DisplayName("upsertAuthProvider — BASIC provider with $2a$ hash → NOT re-hashed")
    void upsertBasicProvider_alreadyHashed_skipsHashing() {
        String existingHash = BCRYPT.encode("existingPass");
        AuthProviderDto provider = basicProvider("bp-2", existingHash);

        service.upsertAuthProvider(provider, "test-user");

        // Should remain unchanged (exact same hash)
        assertThat(provider.getPassword()).isEqualTo(existingHash);
    }

    @Test
    @DisplayName("upsertAuthProvider — BASIC provider with masked password → preserved from existing")
    void upsertBasicProvider_maskedPassword_preservedFromExisting() {
        // Set up existing config with a hashed password
        String existingHash = BCRYPT.encode("storedPass");
        GatewayConfigDto existingConfig = GatewayConfigDto.builder()
                .authProviders(List.of(basicProvider("bp-3", existingHash)))
                .build();
        @SuppressWarnings("unchecked")
        Map<String, Object> configMap = MAPPER.convertValue(existingConfig, Map.class);
        when(routeServiceConfigClient.fetchConfig()).thenReturn(configMap);

        // Submit with masked password
        AuthProviderDto provider = basicProvider("bp-3", Sensitive.MASK);

        service.upsertAuthProvider(provider, "test-user");

        // Password should be preserved (the existing hash)
        assertThat(provider.getPassword()).isEqualTo(existingHash);
    }

    @Test
    @DisplayName("upsertAuthProvider — BASIC provider with null password → left null")
    void upsertBasicProvider_nullPassword_leftNull() {
        AuthProviderDto provider = basicProvider("bp-4", null);

        service.upsertAuthProvider(provider, "test-user");

        assertThat(provider.getPassword()).isNull();
    }

    @Test
    @DisplayName("upsertAuthProvider — BASIC provider with blank password → left blank")
    void upsertBasicProvider_blankPassword_leftBlank() {
        AuthProviderDto provider = basicProvider("bp-5", "   ");

        service.upsertAuthProvider(provider, "test-user");

        assertThat(provider.getPassword()).isEqualTo("   ");
    }

    // ─── Non-BASIC providers — no hashing ─────────────────────────────────────

    @Test
    @DisplayName("upsertAuthProvider — OAUTH2 provider password → NOT hashed")
    void upsertOauth2Provider_passwordNotHashed() {
        // OAuth2 providers with a password field should NOT be BCrypt-hashed
        AuthProviderDto provider = oauth2Provider("op-1", "client-secret-value");

        service.upsertAuthProvider(provider, "test-user");

        // clientSecret should remain unchanged (not BCrypt'd)
        assertThat(provider.getClientSecret()).isEqualTo("client-secret-value");
        assertThat(provider.getClientSecret()).doesNotStartWith("$2");
    }

    // ─── saveConfig — full config hashing ─────────────────────────────────────

    @Test
    @DisplayName("saveConfig — all BASIC providers in full config are hashed")
    void saveConfig_hashesAllBasicProviders() {
        GatewayConfigDto dto = GatewayConfigDto.builder()
                .authProviders(List.of(
                        basicProvider("bp-a", "plainPass1"),
                        basicProvider("bp-b", "plainPass2"),
                        oauth2Provider("op-a", "secret")
                ))
                .build();

        service.saveConfig(dto, "admin");

        // Verify the result persisted has all BASIC passwords hashed
        verify(routeServiceConfigClient).saveConfig(argThat(map -> {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> providers = (List<Map<String, Object>>) map.get("authProviders");
            if (providers == null) return false;

            for (Map<String, Object> p : providers) {
                String type = (String) p.get("type");
                String password = (String) p.get("password");
                if ("BASIC".equalsIgnoreCase(type) && password != null && !password.isBlank()) {
                    if (!password.startsWith("$2")) return false;
                }
            }
            return true;
        }), eq("admin"), eq("full"));
    }
}


