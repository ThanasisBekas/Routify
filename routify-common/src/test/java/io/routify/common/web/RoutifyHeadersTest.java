package io.routify.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link RoutifyHeaders#resolveActor(String, String)}.
 *
 * <p>Verifies the three-tier resolution order:
 * <ol>
 *   <li>{@code userId} header value (when non-null, non-blank)</li>
 *   <li>Authenticated principal name</li>
 *   <li>{@code "system"} fallback</li>
 * </ol>
 */
class RoutifyHeadersTest {

    // ─── resolveActor ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("resolveActor() resolution order")
    class ResolveActor {

        @Test
        @DisplayName("userId takes precedence when non-null and non-blank")
        void userIdTakesPrecedence() {
            String actor = RoutifyHeaders.resolveActor("user-123", "principal@routify.io");
            assertThat(actor).isEqualTo("user-123");
        }

        @Test
        @DisplayName("Falls back to principalName when userId is null")
        void fallsBackToPrincipalWhenUserIdNull() {
            String actor = RoutifyHeaders.resolveActor(null, "principal@routify.io");
            assertThat(actor).isEqualTo("principal@routify.io");
        }

        @Test
        @DisplayName("Falls back to principalName when userId is blank")
        void fallsBackToPrincipalWhenUserIdBlank() {
            String actor = RoutifyHeaders.resolveActor("   ", "principal@routify.io");
            assertThat(actor).isEqualTo("principal@routify.io");
        }

        @Test
        @DisplayName("Falls back to principalName when userId is empty")
        void fallsBackToPrincipalWhenUserIdEmpty() {
            String actor = RoutifyHeaders.resolveActor("", "principal@routify.io");
            assertThat(actor).isEqualTo("principal@routify.io");
        }

        @Test
        @DisplayName("Falls back to 'system' when both userId and principalName are null")
        void fallsBackToSystemWhenBothNull() {
            String actor = RoutifyHeaders.resolveActor(null, null);
            assertThat(actor).isEqualTo("system");
        }

        @Test
        @DisplayName("Falls back to 'system' when userId is blank and principalName is null")
        void fallsBackToSystemWhenUserIdBlankAndPrincipalNull() {
            String actor = RoutifyHeaders.resolveActor("  ", null);
            assertThat(actor).isEqualTo("system");
        }

        @Test
        @DisplayName("UserId with whitespace is preserved (only blank check, no trim)")
        void userIdWithContentNotTrimmed() {
            String actor = RoutifyHeaders.resolveActor(" user-with-spaces ", "principal");
            assertThat(actor).isEqualTo(" user-with-spaces ");
        }

        @Test
        @DisplayName("PrincipalName blank string is returned as-is (not system)")
        void principalNameBlankIsReturnedAsIs() {
            // principalName is returned as-is when non-null — the method does not check for blank
            String actor = RoutifyHeaders.resolveActor(null, "   ");
            assertThat(actor).isEqualTo("   ");
        }
    }

    // ─── Header constants ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Header constant values")
    class HeaderConstants {

        @Test
        @DisplayName("TENANT_ID is 'X-Tenant-Id'")
        void tenantId() {
            assertThat(RoutifyHeaders.TENANT_ID).isEqualTo("X-Tenant-Id");
        }

        @Test
        @DisplayName("AUTH_USER_ID is 'X-Auth-User-Id'")
        void authUserId() {
            assertThat(RoutifyHeaders.AUTH_USER_ID).isEqualTo("X-Auth-User-Id");
        }

        @Test
        @DisplayName("CORRELATION_ID is 'X-Correlation-Id'")
        void correlationId() {
            assertThat(RoutifyHeaders.CORRELATION_ID).isEqualTo("X-Correlation-Id");
        }

        @Test
        @DisplayName("API_KEY is 'X-Api-Key'")
        void apiKey() {
            assertThat(RoutifyHeaders.API_KEY).isEqualTo("X-Api-Key");
        }

        @Test
        @DisplayName("REPLAY_MARKER is 'X-Routify-Replay'")
        void replayMarker() {
            assertThat(RoutifyHeaders.REPLAY_MARKER).isEqualTo("X-Routify-Replay");
        }

        @Test
        @DisplayName("All header constants follow X-* naming convention")
        void allHeadersFollowConvention() {
            assertThat(RoutifyHeaders.TENANT_ID).startsWith("X-");
            assertThat(RoutifyHeaders.AUTH_USER_ID).startsWith("X-");
            assertThat(RoutifyHeaders.AUTH_TENANT_ID).startsWith("X-");
            assertThat(RoutifyHeaders.AUTH_ROLE).startsWith("X-");
            assertThat(RoutifyHeaders.AUTH_EMAIL).startsWith("X-");
            assertThat(RoutifyHeaders.AUTH_TYPE).startsWith("X-");
            assertThat(RoutifyHeaders.USER_ID).startsWith("X-");
            assertThat(RoutifyHeaders.CORRELATION_ID).startsWith("X-");
            assertThat(RoutifyHeaders.ROUTE_VERSION).startsWith("X-");
            assertThat(RoutifyHeaders.REPLAY_MARKER).startsWith("X-");
            assertThat(RoutifyHeaders.API_KEY).startsWith("X-");
            assertThat(RoutifyHeaders.AUTH_TOKEN).startsWith("X-");
        }
    }

    // ─── Utility class constraints ────────────────────────────────────────────

    @Nested
    @DisplayName("Utility class constraints")
    class UtilityClassConstraints {

        @Test
        @DisplayName("RoutifyHeaders has a private constructor")
        void privateConstructor() throws Exception {
            var constructor = RoutifyHeaders.class.getDeclaredConstructor();
            assertThat(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("RoutifyHeaders is final")
        void finalClass() {
            assertThat(java.lang.reflect.Modifier.isFinal(RoutifyHeaders.class.getModifiers())).isTrue();
        }
    }
}

