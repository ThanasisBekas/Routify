package io.routify.common.event;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.routify.common.domain.FilterType;
import io.routify.common.domain.RouteEnvironment;
import io.routify.common.domain.TenantPlan;
import io.routify.common.domain.UserRole;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip serialisation tests for every {@link CommandEvent} sealed subtype.
 *
 * <p>A single broken {@code "type"} discriminator silently drops commands
 * (Jackson falls back to {@link CommandEvent.Unknown}). These tests guarantee
 * that every concrete command survives {@code serialize → deserialize} and
 * comes back as the correct Java type with all fields intact.
 */
class CommandEventSerializationTest {

    private static ObjectMapper mapper;

    // Shared fixtures
    private static final UUID CMD_ID    = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ENTITY_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID OTHER_ID  = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final String ACTOR   = "admin@routify.io";
    private static final Instant NOW    = Instant.parse("2026-04-05T10:00:00Z");

    @BeforeAll
    static void setup() {
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // Match Spring Boot's default: ignore unknown properties during deserialization.
        // This is critical for CommandEvent.Unknown (zero-field fallback record).
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    // ─── Utility ──────────────────────────────────────────────────────────────

    /**
     * Serialise a CommandEvent to JSON, then deserialise back and assert:
     * 1. The result is the same concrete type (not Unknown).
     * 2. The result equals the original (record equality).
     * 3. The JSON contains the correct "type" discriminator.
     */
    private void assertRoundTrip(CommandEvent original, String expectedType) throws Exception {
        String json = mapper.writeValueAsString(original);

        // Verify discriminator is present in the JSON
        assertThat(json).contains("\"type\":\"" + expectedType + "\"");

        // Deserialise back to the sealed interface
        CommandEvent deserialized = mapper.readValue(json, CommandEvent.class);

        // Must not fall back to Unknown
        assertThat(deserialized).isNotInstanceOf(CommandEvent.Unknown.class);

        // Must be the same concrete type
        assertThat(deserialized).isInstanceOf(original.getClass());

        // All fields must match (record equals)
        assertThat(deserialized).isEqualTo(original);

        // Common interface methods must survive
        assertThat(deserialized.commandId()).isEqualTo(original.commandId());
        assertThat(deserialized.tenantId()).isEqualTo(original.tenantId());
        assertThat(deserialized.requestedBy()).isEqualTo(original.requestedBy());
        assertThat(deserialized.issuedAt()).isEqualTo(original.issuedAt());
    }

    // ─── Parameterised: every subtype in one pass ─────────────────────────────

    static Stream<Arguments> allCommandEvents() {
        return Stream.of(
            // Route commands
            Arguments.of(
                new CommandEvent.CreateRoute(CMD_ID, TENANT_ID, ACTOR, NOW,
                    "users-api", "User service routes", "/api/users/**", "GET,POST",
                    "http://user-service:8080", "1", Map.of("timeout", 5000), RouteEnvironment.PRODUCTION),
                "CREATE_ROUTE"
            ),
            Arguments.of(
                new CommandEvent.UpdateRoute(CMD_ID, TENANT_ID, ACTOR, NOW,
                    ENTITY_ID, "users-api-v2", "Updated", "/api/v2/users/**", "GET,POST,PUT",
                    "http://user-service:8080", "1", Map.of("timeout", 10000)),
                "UPDATE_ROUTE"
            ),
            Arguments.of(
                new CommandEvent.ActivateRoute(CMD_ID, TENANT_ID, ACTOR, NOW, ENTITY_ID),
                "ACTIVATE_ROUTE"
            ),
            Arguments.of(
                new CommandEvent.DeactivateRoute(CMD_ID, TENANT_ID, ACTOR, NOW, ENTITY_ID),
                "DEACTIVATE_ROUTE"
            ),
            Arguments.of(
                new CommandEvent.DeleteRoute(CMD_ID, TENANT_ID, ACTOR, NOW, ENTITY_ID),
                "DELETE_ROUTE"
            ),

            // Filter commands
            Arguments.of(
                new CommandEvent.AttachFilter(CMD_ID, TENANT_ID, ACTOR, NOW,
                    ENTITY_ID, OTHER_ID, 1, "PRE"),
                "ATTACH_FILTER"
            ),
            Arguments.of(
                new CommandEvent.DetachFilter(CMD_ID, TENANT_ID, ACTOR, NOW, ENTITY_ID, OTHER_ID),
                "DETACH_FILTER"
            ),
            Arguments.of(
                new CommandEvent.CreateFilter(CMD_ID, TENANT_ID, ACTOR, NOW,
                    "rate-limiter", "10 req/s", FilterType.RATE_LIMIT_FIXED_WINDOW,
                    Map.of("limit", 10, "windowSeconds", 1), Map.of()),
                "CREATE_FILTER"
            ),
            Arguments.of(
                new CommandEvent.UpdateFilter(CMD_ID, TENANT_ID, ACTOR, NOW,
                    ENTITY_ID, "rate-limiter-v2", "20 req/s",
                    Map.of("limit", 20), Map.of()),
                "UPDATE_FILTER"
            ),
            Arguments.of(
                new CommandEvent.DeleteFilter(CMD_ID, TENANT_ID, ACTOR, NOW, ENTITY_ID),
                "DELETE_FILTER"
            ),

            // User commands
            Arguments.of(
                new CommandEvent.CreateUser(CMD_ID, TENANT_ID, ACTOR, NOW,
                    "jane.doe", "jane@routify.io", "secret123", UserRole.TENANT_ADMIN),
                "CREATE_USER"
            ),
            Arguments.of(
                new CommandEvent.UpdateUser(CMD_ID, TENANT_ID, ACTOR, NOW,
                    ENTITY_ID, "jane.doe", "jane@routify.io", UserRole.OPERATOR),
                "UPDATE_USER"
            ),
            Arguments.of(
                new CommandEvent.DeleteUser(CMD_ID, TENANT_ID, ACTOR, NOW, ENTITY_ID),
                "DELETE_USER"
            ),

            // Auth commands
            Arguments.of(
                new CommandEvent.Logout(CMD_ID, null, ACTOR, NOW, "refresh-token-jti-value"),
                "LOGOUT"
            ),

            // Certificate commands
            Arguments.of(
                new CommandEvent.UploadCertificate(CMD_ID, TENANT_ID, ACTOR, NOW,
                    OTHER_ID, "primary", "my-cert", "Production TLS cert",
                    "PEM", "-----BEGIN CERTIFICATE-----\nMIIB...", "-----BEGIN PRIVATE KEY-----\nMIIE..."),
                "UPLOAD_CERTIFICATE"
            ),
            Arguments.of(
                new CommandEvent.RevokeCertificate(CMD_ID, TENANT_ID, ACTOR, NOW, ENTITY_ID),
                "REVOKE_CERTIFICATE"
            ),
            Arguments.of(
                new CommandEvent.DeleteCertificate(CMD_ID, TENANT_ID, ACTOR, NOW, ENTITY_ID),
                "DELETE_CERTIFICATE"
            ),
            Arguments.of(
                new CommandEvent.MapCertificateToGateway(CMD_ID, TENANT_ID, ACTOR, NOW,
                    ENTITY_ID, "default-tls"),
                "MAP_CERTIFICATE_TO_GATEWAY"
            ),
            Arguments.of(
                new CommandEvent.UnmapCertificateFromGateway(CMD_ID, TENANT_ID, ACTOR, NOW, ENTITY_ID),
                "UNMAP_CERTIFICATE_FROM_GATEWAY"
            ),

            // Cert-group commands
            Arguments.of(
                new CommandEvent.CreateCertGroup(CMD_ID, TENANT_ID, ACTOR, NOW,
                    "mtls-clients", "mTLS Clients", "Client certificates for mTLS"),
                "CREATE_CERT_GROUP"
            ),
            Arguments.of(
                new CommandEvent.UpdateCertGroup(CMD_ID, TENANT_ID, ACTOR, NOW,
                    ENTITY_ID, "mtls-clients-v2", "Updated description"),
                "UPDATE_CERT_GROUP"
            ),
            Arguments.of(
                new CommandEvent.ArchiveCertGroup(CMD_ID, TENANT_ID, ACTOR, NOW, ENTITY_ID),
                "ARCHIVE_CERT_GROUP"
            ),
            Arguments.of(
                new CommandEvent.DeleteCertGroup(CMD_ID, TENANT_ID, ACTOR, NOW, ENTITY_ID),
                "DELETE_CERT_GROUP"
            ),
            Arguments.of(
                new CommandEvent.AddCertToGroup(CMD_ID, TENANT_ID, ACTOR, NOW,
                    ENTITY_ID, OTHER_ID, "client-1"),
                "ADD_CERT_TO_GROUP"
            ),
            Arguments.of(
                new CommandEvent.RemoveCertFromGroup(CMD_ID, TENANT_ID, ACTOR, NOW,
                    ENTITY_ID, OTHER_ID),
                "REMOVE_CERT_FROM_GROUP"
            ),

            // Tenant commands (RabbitMQ sync)
            Arguments.of(
                new CommandEvent.CreateTenant(CMD_ID, null, ACTOR, NOW,
                    "Acme Corp", "acme", TenantPlan.PRO, "admin@acme.com"),
                "CREATE_TENANT"
            ),
            Arguments.of(
                new CommandEvent.UpdateTenant(CMD_ID, TENANT_ID, ACTOR, NOW,
                    "Acme Corp v2", TenantPlan.ENTERPRISE, "cto@acme.com"),
                "UPDATE_TENANT"
            ),
            Arguments.of(
                new CommandEvent.SuspendTenant(CMD_ID, TENANT_ID, ACTOR, NOW, "Non-payment"),
                "SUSPEND_TENANT"
            ),
            Arguments.of(
                new CommandEvent.ReactivateTenant(CMD_ID, TENANT_ID, ACTOR, NOW),
                "REACTIVATE_TENANT"
            ),

            // Gateway config commands (RabbitMQ sync)
            Arguments.of(
                new CommandEvent.SaveGatewayConfig(CMD_ID, null, ACTOR, NOW,
                    "cors", Map.of("allowedOrigins", "*", "allowedMethods", "GET,POST")),
                "SAVE_GATEWAY_CONFIG"
            )
        );
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("allCommandEvents")
    @DisplayName("Round-trip: serialize → deserialize preserves type and fields")
    void roundTrip(CommandEvent command, String expectedType) throws Exception {
        assertRoundTrip(command, expectedType);
    }

    // ─── Edge cases ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Unknown type discriminator deserialises to CommandEvent.Unknown")
        void unknownTypeDeserializesToFallback() throws Exception {
            String json = """
                {"type":"NONEXISTENT_COMMAND","commandId":"11111111-1111-1111-1111-111111111111"}
                """;
            CommandEvent result = mapper.readValue(json, CommandEvent.class);
            assertThat(result).isInstanceOf(CommandEvent.Unknown.class);
            assertThat(result.commandId()).isNull();
        }

        @Test
        @DisplayName("Missing type discriminator deserialises to CommandEvent.Unknown")
        void missingTypeDeserializesToFallback() throws Exception {
            String json = """
                {"commandId":"11111111-1111-1111-1111-111111111111","tenantId":null}
                """;
            CommandEvent result = mapper.readValue(json, CommandEvent.class);
            assertThat(result).isInstanceOf(CommandEvent.Unknown.class);
        }

        @Test
        @DisplayName("Null tenantId fields round-trip correctly (e.g. Logout)")
        void nullTenantIdRoundTrips() throws Exception {
            var logout = new CommandEvent.Logout(CMD_ID, null, ACTOR, NOW, "refresh-token");
            String json = mapper.writeValueAsString(logout);
            CommandEvent result = mapper.readValue(json, CommandEvent.class);
            assertThat(result).isInstanceOf(CommandEvent.Logout.class);
            assertThat(result.tenantId()).isNull();
        }

        @Test
        @DisplayName("Empty Map fields round-trip correctly")
        void emptyMapRoundTrips() throws Exception {
            var cmd = new CommandEvent.CreateRoute(CMD_ID, TENANT_ID, ACTOR, NOW,
                    "name", "desc", "/path", "GET", "http://up", "0", Map.of(), RouteEnvironment.PRODUCTION);
            String json = mapper.writeValueAsString(cmd);
            CommandEvent result = mapper.readValue(json, CommandEvent.class);
            assertThat(result).isEqualTo(cmd);
        }

        @Test
        @DisplayName("Nullable privateKey in UploadCertificate round-trips as null")
        void nullablePrivateKeyRoundTrips() throws Exception {
            var cmd = new CommandEvent.UploadCertificate(CMD_ID, TENANT_ID, ACTOR, NOW,
                    OTHER_ID, "alias", "cert", "desc", "PEM", "-----BEGIN CERTIFICATE-----", null);
            String json = mapper.writeValueAsString(cmd);
            CommandEvent result = mapper.readValue(json, CommandEvent.class);
            assertThat(result).isEqualTo(cmd);
            assertThat(((CommandEvent.UploadCertificate) result).privateKey()).isNull();
        }

        @Test
        @DisplayName("All sealed permits are covered by @JsonSubTypes (completeness check)")
        void allPermitsHaveJsonSubTypes() {
            // CommandEvent has 30 permitted subtypes (29 concrete + Unknown).
            // The @JsonSubTypes annotation should list 29 entries (Unknown is the defaultImpl).
            var subtypes = CommandEvent.class.getAnnotation(com.fasterxml.jackson.annotation.JsonSubTypes.class);
            assertThat(subtypes).isNotNull();

            var permittedSubclasses = CommandEvent.class.getPermittedSubclasses();
            assertThat(permittedSubclasses).isNotNull();

            // -1 for Unknown (handled by defaultImpl, not @JsonSubTypes.Type)
            int expectedSubTypeEntries = permittedSubclasses.length - 1;
            assertThat(subtypes.value()).hasSize(expectedSubTypeEntries);
        }
    }
}

