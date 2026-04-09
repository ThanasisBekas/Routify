package io.routify.admin.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.routify.admin.client.RouteFilterMessagingClient;
import io.routify.admin.gateway.dto.GatewayConfigDto;
import io.routify.admin.gateway.dto.GatewayConfigDto.AuthProviderDto;
import io.routify.admin.gateway.dto.GatewayConfigDto.DownstreamCredentialDto;
import io.routify.admin.gateway.dto.GatewayConfigDto.ProxyConfigDto;
import io.routify.common.crypto.FieldEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the AES-256-GCM encryption logic in {@link GatewayConfigService}.
 *
 * <p>Covers:
 * <ul>
 *   <li>BASIC auth provider passwords are AES-encrypted on upsert</li>
 *   <li>Already-encrypted passwords ({enc} prefix) are NOT re-encrypted</li>
 *   <li>Legacy BCrypt hashes ($2 prefix) are preserved (not re-encrypted)</li>
 *   <li>Null/blank passwords are left untouched</li>
 *   <li>Non-BASIC auth providers' clientSecret is AES-encrypted</li>
 *   <li>saveConfig() encrypts all secrets in the full config</li>
 *   <li>getConfigDecrypted() returns plaintext passwords</li>
 *   <li>getAuthProvidersDecrypted() returns plaintext passwords</li>
 * </ul>
 */
class GatewayConfigServicePasswordHashingTest {

    /** A valid 32-byte Base64-encoded AES key for testing. */
    private static final String TEST_AES_KEY =
            Base64.getEncoder().encodeToString(new byte[32]); // 32 zero bytes — test only

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private FieldEncryptionService fieldEncryptionService;
    private StringRedisTemplate redisTemplate;
    private GatewayActuatorClient gatewayActuatorClient;
    private RouteServiceConfigClient routeServiceConfigClient;
    private RouteFilterMessagingClient routeFilterMessagingClient;
    private GatewayConfigService service;

    @BeforeEach
    void setUp() {
        fieldEncryptionService = new FieldEncryptionService(TEST_AES_KEY);
        redisTemplate = mock(StringRedisTemplate.class);
        gatewayActuatorClient = mock(GatewayActuatorClient.class);
        routeServiceConfigClient = mock(RouteServiceConfigClient.class);
        routeFilterMessagingClient = mock(RouteFilterMessagingClient.class);

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

        service = new GatewayConfigService(
                redisTemplate, MAPPER, gatewayActuatorClient,
                routeServiceConfigClient, routeFilterMessagingClient,
                fieldEncryptionService);
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

    // ─── upsertAuthProvider — BASIC AES encryption ─────────────────────────────

    @Test
    @DisplayName("upsertAuthProvider — BASIC provider with plain-text password → AES-encrypted")
    void upsertBasicProvider_plainTextPassword_isEncrypted() {
        AuthProviderDto provider = basicProvider("bp-1", "myPlainPassword");

        service.upsertAuthProvider(provider, "test-user");

        assertThat(provider.getPassword()).startsWith("{enc}");
        assertThat(fieldEncryptionService.decrypt(provider.getPassword())).isEqualTo("myPlainPassword");
    }

    @Test
    @DisplayName("upsertAuthProvider — BASIC provider with {enc} password → NOT re-encrypted")
    void upsertBasicProvider_alreadyEncrypted_skipsEncryption() {
        String encrypted = fieldEncryptionService.encrypt("existingPass");
        AuthProviderDto provider = basicProvider("bp-2", encrypted);

        service.upsertAuthProvider(provider, "test-user");

        assertThat(provider.getPassword()).isEqualTo(encrypted);
    }

    @Test
    @DisplayName("upsertAuthProvider — BASIC provider with legacy BCrypt hash → preserved")
    void upsertBasicProvider_legacyBcrypt_preserved() {
        String bcryptHash = "$2a$12$Gm/..3ub70IQ2p0zuNmd..Ry1rEgX0MhgayIfps/SanBDYM43DFHe";
        AuthProviderDto provider = basicProvider("bp-3", bcryptHash);

        service.upsertAuthProvider(provider, "test-user");

        assertThat(provider.getPassword()).isEqualTo(bcryptHash);
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

    // ─── OAuth2 providers — clientSecret encryption ────────────────────────────

    @Test
    @DisplayName("upsertAuthProvider — OAUTH2 provider clientSecret → AES-encrypted")
    void upsertOauth2Provider_clientSecretEncrypted() {
        AuthProviderDto provider = oauth2Provider("op-1", "client-secret-value");

        service.upsertAuthProvider(provider, "test-user");

        assertThat(provider.getClientSecret()).startsWith("{enc}");
        assertThat(fieldEncryptionService.decrypt(provider.getClientSecret()))
                .isEqualTo("client-secret-value");
    }

    @Test
    @DisplayName("upsertAuthProvider — OAUTH2 provider already-encrypted clientSecret → NOT re-encrypted")
    void upsertOauth2Provider_alreadyEncryptedSecret_skips() {
        String encrypted = fieldEncryptionService.encrypt("my-secret");
        AuthProviderDto provider = oauth2Provider("op-2", encrypted);

        service.upsertAuthProvider(provider, "test-user");

        assertThat(provider.getClientSecret()).isEqualTo(encrypted);
    }

    // ─── saveConfig — full config encryption ──────────────────────────────────

    @Test
    @DisplayName("saveConfig — all secrets in full config are AES-encrypted")
    void saveConfig_encryptsAllSecrets() {
        GatewayConfigDto dto = GatewayConfigDto.builder()
                .authProviders(List.of(
                        basicProvider("bp-a", "plainPass1"),
                        basicProvider("bp-b", "plainPass2"),
                        oauth2Provider("op-a", "secret")
                ))
                .build();

        service.saveConfig(dto, "admin");

        verify(routeServiceConfigClient).saveConfig(argThat(map -> {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> providers = (List<Map<String, Object>>) map.get("authProviders");
            if (providers == null) return false;

            for (Map<String, Object> p : providers) {
                String type = (String) p.get("type");
                String password = (String) p.get("password");
                String clientSecret = (String) p.get("clientSecret");

                if ("BASIC".equalsIgnoreCase(type) && password != null && !password.isBlank()) {
                    if (!password.startsWith("{enc}")) return false;
                }
                if (clientSecret != null && !clientSecret.isBlank()) {
                    if (!clientSecret.startsWith("{enc}")) return false;
                }
            }
            return true;
        }), eq("admin"), eq("full"));
    }

    // ─── Decryption on read ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Read path — decryption")
    class ReadPathDecryption {

        @Test
        @DisplayName("getAuthProvidersDecrypted — AES-encrypted passwords are decrypted to plaintext")
        void getAuthProvidersDecrypted_decryptsPasswords() {
            String encrypted = fieldEncryptionService.encrypt("mySecret");
            GatewayConfigDto config = GatewayConfigDto.builder()
                    .authProviders(List.of(basicProvider("bp-d", encrypted)))
                    .build();
            @SuppressWarnings("unchecked")
            Map<String, Object> configMap = MAPPER.convertValue(config, Map.class);
            when(routeServiceConfigClient.fetchConfig()).thenReturn(configMap);

            List<AuthProviderDto> providers = service.getAuthProvidersDecrypted();

            assertThat(providers).hasSize(1);
            assertThat(providers.getFirst().getPassword()).isEqualTo("mySecret");
        }

        @Test
        @DisplayName("getAuthProvidersDecrypted — legacy BCrypt passwords are returned as-is")
        void getAuthProvidersDecrypted_legacyBcryptPassedThrough() {
            String bcrypt = "$2a$12$SomeHash";
            GatewayConfigDto config = GatewayConfigDto.builder()
                    .authProviders(List.of(basicProvider("bp-e", bcrypt)))
                    .build();
            @SuppressWarnings("unchecked")
            Map<String, Object> configMap = MAPPER.convertValue(config, Map.class);
            when(routeServiceConfigClient.fetchConfig()).thenReturn(configMap);

            List<AuthProviderDto> providers = service.getAuthProvidersDecrypted();

            assertThat(providers).hasSize(1);
            assertThat(providers.getFirst().getPassword()).isEqualTo(bcrypt);
        }

        @Test
        @DisplayName("getConfigDecrypted — decrypts all secret fields across the config")
        void getConfigDecrypted_decryptsAllSecrets() {
            String encPw = fieldEncryptionService.encrypt("password123");
            String encSecret = fieldEncryptionService.encrypt("oauth-secret");

            GatewayConfigDto config = GatewayConfigDto.builder()
                    .authProviders(List.of(
                            basicProvider("bp-f", encPw),
                            oauth2Provider("op-f", encSecret)
                    ))
                    .build();
            @SuppressWarnings("unchecked")
            Map<String, Object> configMap = MAPPER.convertValue(config, Map.class);
            when(routeServiceConfigClient.fetchConfig()).thenReturn(configMap);

            GatewayConfigDto result = service.getConfigDecrypted();

            assertThat(result.getAuthProviders()).hasSize(2);

            AuthProviderDto basic = result.getAuthProviders().stream()
                    .filter(p -> "BASIC".equals(p.getType())).findFirst().orElseThrow();
            assertThat(basic.getPassword()).isEqualTo("password123");

            AuthProviderDto oauth2 = result.getAuthProviders().stream()
                    .filter(p -> "OAUTH2_CLIENT_CREDENTIALS".equals(p.getType())).findFirst().orElseThrow();
            assertThat(oauth2.getClientSecret()).isEqualTo("oauth-secret");
        }
    }
}
