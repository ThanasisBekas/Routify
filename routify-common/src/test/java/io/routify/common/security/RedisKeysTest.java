package io.routify.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link RedisKeys} constants — validates key prefix values, naming
 * conventions, and utility class constraints.
 *
 * <p>These tests guard against accidental key prefix changes that would break
 * cross-service compatibility (gateway reads what identity-service writes).
 */
class RedisKeysTest {

    // ─── Key prefix values ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Key prefix values")
    class KeyPrefixValues {

        @Test
        @DisplayName("BLOCKLIST_PREFIX is 'routify:token:blocklist:'")
        void blocklistPrefix() {
            assertThat(RedisKeys.BLOCKLIST_PREFIX).isEqualTo("routify:token:blocklist:");
        }

        @Test
        @DisplayName("APIKEY_PREFIX is 'routify:apikeys:'")
        void apikeyPrefix() {
            assertThat(RedisKeys.APIKEY_PREFIX).isEqualTo("routify:apikeys:");
        }

        @Test
        @DisplayName("QUOTA_PREFIX is 'routify:quota:'")
        void quotaPrefix() {
            assertThat(RedisKeys.QUOTA_PREFIX).isEqualTo("routify:quota:");
        }

        @Test
        @DisplayName("QUOTA_WARNED_PREFIX is 'routify:quota:warned:'")
        void quotaWarnedPrefix() {
            assertThat(RedisKeys.QUOTA_WARNED_PREFIX).isEqualTo("routify:quota:warned:");
        }

        @Test
        @DisplayName("GATEWAY_INSTANCES_PREFIX is 'routify:gateway:instances:'")
        void gatewayInstancesPrefix() {
            assertThat(RedisKeys.GATEWAY_INSTANCES_PREFIX).isEqualTo("routify:gateway:instances:");
        }

        @Test
        @DisplayName("GATEWAY_INSTANCES_SET is 'routify:gateway:instances'")
        void gatewayInstancesSet() {
            assertThat(RedisKeys.GATEWAY_INSTANCES_SET).isEqualTo("routify:gateway:instances");
        }

        @Test
        @DisplayName("GATEWAY_CONFIG_VERSION is 'routify:gateway:config-version'")
        void gatewayConfigVersion() {
            assertThat(RedisKeys.GATEWAY_CONFIG_VERSION).isEqualTo("routify:gateway:config-version");
        }
    }

    // ─── Naming conventions ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Naming conventions")
    class NamingConventions {

        @Test
        @DisplayName("All prefix constants start with 'routify:'")
        void allPrefixesStartWithRoutify() {
            assertThat(RedisKeys.BLOCKLIST_PREFIX).startsWith("routify:");
            assertThat(RedisKeys.APIKEY_PREFIX).startsWith("routify:");
            assertThat(RedisKeys.QUOTA_PREFIX).startsWith("routify:");
            assertThat(RedisKeys.QUOTA_WARNED_PREFIX).startsWith("routify:");
            assertThat(RedisKeys.GATEWAY_INSTANCES_PREFIX).startsWith("routify:");
            assertThat(RedisKeys.GATEWAY_INSTANCES_SET).startsWith("routify:");
            assertThat(RedisKeys.GATEWAY_CONFIG_VERSION).startsWith("routify:");
        }

        @Test
        @DisplayName("Prefix constants end with ':' (except non-prefix keys)")
        void prefixesEndWithColon() {
            assertThat(RedisKeys.BLOCKLIST_PREFIX).endsWith(":");
            assertThat(RedisKeys.APIKEY_PREFIX).endsWith(":");
            assertThat(RedisKeys.QUOTA_PREFIX).endsWith(":");
            assertThat(RedisKeys.QUOTA_WARNED_PREFIX).endsWith(":");
            assertThat(RedisKeys.GATEWAY_INSTANCES_PREFIX).endsWith(":");
        }

        @Test
        @DisplayName("Non-prefix keys do not end with ':'")
        void nonPrefixKeysDoNotEndWithColon() {
            assertThat(RedisKeys.GATEWAY_INSTANCES_SET).doesNotEndWith(":");
            assertThat(RedisKeys.GATEWAY_CONFIG_VERSION).doesNotEndWith(":");
        }

        @Test
        @DisplayName("Key segments use ':' as separator (Redis convention)")
        void colonSeparators() {
            // Verify each prefix uses ':' consistently
            assertThat(RedisKeys.BLOCKLIST_PREFIX.chars().filter(c -> c == ':').count())
                    .as("BLOCKLIST_PREFIX segment count")
                    .isEqualTo(3); // routify:token:blocklist:
            assertThat(RedisKeys.APIKEY_PREFIX.chars().filter(c -> c == ':').count())
                    .as("APIKEY_PREFIX segment count")
                    .isEqualTo(2); // routify:apikeys:
        }
    }

    // ─── Key construction examples ────────────────────────────────────────────

    @Nested
    @DisplayName("Key construction examples")
    class KeyConstruction {

        @Test
        @DisplayName("Blocklist key for a JTI")
        void blocklistKey() {
            String key = RedisKeys.BLOCKLIST_PREFIX + "abc-123-jti";
            assertThat(key).isEqualTo("routify:token:blocklist:abc-123-jti");
        }

        @Test
        @DisplayName("API key hash key")
        void apikeyKey() {
            String key = RedisKeys.APIKEY_PREFIX + "rk_live_xxxxxxxxxxxx";
            assertThat(key).isEqualTo("routify:apikeys:rk_live_xxxxxxxxxxxx");
        }

        @Test
        @DisplayName("Quota key for a tenant and month")
        void quotaKey() {
            String key = RedisKeys.QUOTA_PREFIX + "tenant-uuid:2026-04";
            assertThat(key).isEqualTo("routify:quota:tenant-uuid:2026-04");
        }

        @Test
        @DisplayName("Gateway instance key")
        void gatewayInstanceKey() {
            String key = RedisKeys.GATEWAY_INSTANCES_PREFIX + "gw-instance-1";
            assertThat(key).isEqualTo("routify:gateway:instances:gw-instance-1");
        }
    }

    // ─── Utility class constraints ────────────────────────────────────────────

    @Nested
    @DisplayName("Utility class constraints")
    class UtilityClassConstraints {

        @Test
        @DisplayName("RedisKeys has a private constructor")
        void privateConstructor() throws Exception {
            var constructor = RedisKeys.class.getDeclaredConstructor();
            assertThat(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("RedisKeys is final")
        void finalClass() {
            assertThat(java.lang.reflect.Modifier.isFinal(RedisKeys.class.getModifiers())).isTrue();
        }
    }
}

