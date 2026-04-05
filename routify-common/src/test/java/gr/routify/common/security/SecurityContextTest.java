package gr.routify.common.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lifecycle and behaviour tests for {@link SecurityContext}.
 *
 * <p>Verifies the thread-local {@code set()} → {@code current()} → {@code clear()} contract,
 * cross-thread isolation, role-checking helpers, and edge cases.
 */
class SecurityContextTest {

    private static final UUID USER_ID   = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String USERNAME       = "admin@routify.io";
    private static final String CORRELATION_ID = "corr-001";

    @AfterEach
    void cleanup() {
        SecurityContext.clear();
    }

    // ─── Lifecycle ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Thread-local lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("set() → current() returns the same context")
        void setThenCurrentReturnsSameContext() {
            var ctx = new SecurityContext(USER_ID, TENANT_ID, USERNAME, "SUPER_ADMIN", CORRELATION_ID);
            SecurityContext.set(ctx);

            SecurityContext retrieved = SecurityContext.current();

            assertThat(retrieved).isSameAs(ctx);
            assertThat(retrieved.userId()).isEqualTo(USER_ID);
            assertThat(retrieved.tenantId()).isEqualTo(TENANT_ID);
            assertThat(retrieved.username()).isEqualTo(USERNAME);
            assertThat(retrieved.role()).isEqualTo("SUPER_ADMIN");
            assertThat(retrieved.correlationId()).isEqualTo(CORRELATION_ID);
        }

        @Test
        @DisplayName("current() throws IllegalStateException when no context is set")
        void currentThrowsWhenEmpty() {
            assertThatThrownBy(SecurityContext::current)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No SecurityContext set on current thread");
        }

        @Test
        @DisplayName("clear() removes the context so current() throws")
        void clearRemovesContext() {
            SecurityContext.set(new SecurityContext(USER_ID, TENANT_ID, USERNAME, "VIEWER", CORRELATION_ID));
            SecurityContext.clear();

            assertThatThrownBy(SecurityContext::current)
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("set() overwrites a previously set context")
        void setOverwritesPrevious() {
            var first = new SecurityContext(USER_ID, TENANT_ID, USERNAME, "VIEWER", "corr-1");
            var second = new SecurityContext(
                    UUID.fromString("99999999-9999-9999-9999-999999999999"),
                    TENANT_ID, "other@routify.io", "OPERATOR", "corr-2"
            );

            SecurityContext.set(first);
            SecurityContext.set(second);

            assertThat(SecurityContext.current()).isSameAs(second);
            assertThat(SecurityContext.current().username()).isEqualTo("other@routify.io");
        }

        @Test
        @DisplayName("clear() is idempotent — calling it twice does not throw")
        void clearIsIdempotent() {
            SecurityContext.set(new SecurityContext(USER_ID, TENANT_ID, USERNAME, "VIEWER", CORRELATION_ID));
            SecurityContext.clear();
            SecurityContext.clear(); // second call must not throw
        }
    }

    // ─── Thread Isolation ───────────────────────────────────────────────

    @Nested
    @DisplayName("Thread isolation")
    class ThreadIsolation {

        @Test
        @DisplayName("Context set on one thread is not visible on another thread")
        void contextNotVisibleAcrossThreads() throws InterruptedException {
            SecurityContext.set(new SecurityContext(USER_ID, TENANT_ID, USERNAME, "SUPER_ADMIN", CORRELATION_ID));

            var latch = new CountDownLatch(1);
            var errorRef = new AtomicReference<Throwable>();

            Thread other = new Thread(() -> {
                try {
                    SecurityContext.current();
                    errorRef.set(new AssertionError("Expected IllegalStateException on child thread"));
                } catch (IllegalStateException expected) {
                    // correct — context is not inherited
                } catch (Throwable t) {
                    errorRef.set(t);
                } finally {
                    latch.countDown();
                }
            });
            other.start();
            latch.await();

            assertThat(errorRef.get()).isNull();
            // Original thread still has its context
            assertThat(SecurityContext.current().userId()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("Two threads hold independent contexts")
        void twoThreadsHoldIndependentContexts() throws InterruptedException {
            var ctx1 = new SecurityContext(USER_ID, TENANT_ID, USERNAME, "SUPER_ADMIN", "corr-main");
            SecurityContext.set(ctx1);

            var otherUserId = UUID.fromString("88888888-8888-8888-8888-888888888888");
            var latch = new CountDownLatch(1);
            var capturedRef = new AtomicReference<SecurityContext>();

            Thread other = new Thread(() -> {
                var ctx2 = new SecurityContext(otherUserId, TENANT_ID, "other@routify.io", "VIEWER", "corr-other");
                SecurityContext.set(ctx2);
                capturedRef.set(SecurityContext.current());
                SecurityContext.clear();
                latch.countDown();
            });
            other.start();
            latch.await();

            // Child thread had its own context
            assertThat(capturedRef.get().userId()).isEqualTo(otherUserId);
            assertThat(capturedRef.get().role()).isEqualTo("VIEWER");

            // Main thread still has the original
            assertThat(SecurityContext.current().userId()).isEqualTo(USER_ID);
            assertThat(SecurityContext.current().role()).isEqualTo("SUPER_ADMIN");
        }
    }

    // ─── Role Helpers ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Role helper methods")
    class RoleHelpers {

        @Test
        @DisplayName("hasRole() matches case-insensitively")
        void hasRoleCaseInsensitive() {
            SecurityContext.set(new SecurityContext(USER_ID, TENANT_ID, USERNAME, "SUPER_ADMIN", CORRELATION_ID));
            var ctx = SecurityContext.current();

            assertThat(ctx.hasRole("SUPER_ADMIN")).isTrue();
            assertThat(ctx.hasRole("super_admin")).isTrue();
            assertThat(ctx.hasRole("Super_Admin")).isTrue();
            assertThat(ctx.hasRole("TENANT_ADMIN")).isFalse();
        }

        @Test
        @DisplayName("isSuperAdmin() returns true only for SUPER_ADMIN role")
        void isSuperAdmin() {
            SecurityContext.set(new SecurityContext(USER_ID, TENANT_ID, USERNAME, "SUPER_ADMIN", CORRELATION_ID));
            assertThat(SecurityContext.current().isSuperAdmin()).isTrue();

            SecurityContext.set(new SecurityContext(USER_ID, TENANT_ID, USERNAME, "TENANT_ADMIN", CORRELATION_ID));
            assertThat(SecurityContext.current().isSuperAdmin()).isFalse();

            SecurityContext.set(new SecurityContext(USER_ID, TENANT_ID, USERNAME, "VIEWER", CORRELATION_ID));
            assertThat(SecurityContext.current().isSuperAdmin()).isFalse();

            SecurityContext.set(new SecurityContext(USER_ID, TENANT_ID, USERNAME, "OPERATOR", CORRELATION_ID));
            assertThat(SecurityContext.current().isSuperAdmin()).isFalse();
        }

        @Test
        @DisplayName("isTenantAdmin() returns true for TENANT_ADMIN and SUPER_ADMIN")
        void isTenantAdmin() {
            SecurityContext.set(new SecurityContext(USER_ID, TENANT_ID, USERNAME, "TENANT_ADMIN", CORRELATION_ID));
            assertThat(SecurityContext.current().isTenantAdmin()).isTrue();

            SecurityContext.set(new SecurityContext(USER_ID, TENANT_ID, USERNAME, "SUPER_ADMIN", CORRELATION_ID));
            assertThat(SecurityContext.current().isTenantAdmin()).isTrue();

            SecurityContext.set(new SecurityContext(USER_ID, TENANT_ID, USERNAME, "VIEWER", CORRELATION_ID));
            assertThat(SecurityContext.current().isTenantAdmin()).isFalse();

            SecurityContext.set(new SecurityContext(USER_ID, TENANT_ID, USERNAME, "OPERATOR", CORRELATION_ID));
            assertThat(SecurityContext.current().isTenantAdmin()).isFalse();
        }

        @Test
        @DisplayName("isSuperAdmin() and isTenantAdmin() are case-insensitive")
        void roleHelpersCaseInsensitive() {
            SecurityContext.set(new SecurityContext(USER_ID, TENANT_ID, USERNAME, "super_admin", CORRELATION_ID));
            assertThat(SecurityContext.current().isSuperAdmin()).isTrue();
            assertThat(SecurityContext.current().isTenantAdmin()).isTrue();

            SecurityContext.set(new SecurityContext(USER_ID, TENANT_ID, USERNAME, "tenant_admin", CORRELATION_ID));
            assertThat(SecurityContext.current().isTenantAdmin()).isTrue();
            assertThat(SecurityContext.current().isSuperAdmin()).isFalse();
        }
    }

    // ─── Record Equality ────────────────────────────────────────────────

    @Nested
    @DisplayName("Record equality and field access")
    class RecordEquality {

        @Test
        @DisplayName("Two SecurityContext records with same fields are equal")
        void equalRecords() {
            var a = new SecurityContext(USER_ID, TENANT_ID, USERNAME, "VIEWER", CORRELATION_ID);
            var b = new SecurityContext(USER_ID, TENANT_ID, USERNAME, "VIEWER", CORRELATION_ID);

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("SecurityContext with different fields are not equal")
        void unequalRecords() {
            var a = new SecurityContext(USER_ID, TENANT_ID, USERNAME, "VIEWER", CORRELATION_ID);
            var b = new SecurityContext(USER_ID, TENANT_ID, USERNAME, "OPERATOR", CORRELATION_ID);

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Nullable fields (tenantId, correlationId) are accepted")
        void nullableFields() {
            var ctx = new SecurityContext(USER_ID, null, USERNAME, "SUPER_ADMIN", null);
            SecurityContext.set(ctx);

            assertThat(SecurityContext.current().tenantId()).isNull();
            assertThat(SecurityContext.current().correlationId()).isNull();
            assertThat(SecurityContext.current().userId()).isEqualTo(USER_ID);
        }
    }
}

