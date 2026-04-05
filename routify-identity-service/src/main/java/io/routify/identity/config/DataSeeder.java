package io.routify.identity.config;

import io.routify.common.domain.TenantPlan;
import io.routify.common.domain.UserRole;
import io.routify.identity.domain.AppUser;
import io.routify.identity.domain.Tenant;
import io.routify.identity.repository.TenantRepository;
import io.routify.identity.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Seeds the default admin user and platform tenant on first startup.
 *
 * <p>The initial admin password is resolved in the following order:
 * <ol>
 *   <li>The {@code ADMIN_INITIAL_PASSWORD} environment variable (via
 *       {@code routify.seed.admin-password}).</li>
 *   <li>If that variable is absent or blank, a cryptographically random password is
 *       generated, printed <strong>once</strong> to stdout, and the account is
 *       immediately flagged with {@code must_change_password = true}.</li>
 * </ol>
 *
 * <p>The string {@code "admin"} no longer appears anywhere in this class as a credential.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private static final String DEFAULT_TENANT_SLUG    = "platform";
    private static final String DEFAULT_ADMIN_USERNAME = "admin";
    private static final String DEFAULT_ADMIN_EMAIL    = "admin@routify.io";

    /** Supply via {@code ADMIN_INITIAL_PASSWORD} env var. If blank, a random password is generated. */
    @Value("${routify.seed.admin-password:}")
    private String adminInitialPassword;

    private final TenantRepository tenantRepository;
    private final UserRepository   userRepository;
    private final PasswordEncoder  passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Tenant tenant = tenantRepository.findBySlug(DEFAULT_TENANT_SLUG)
                .orElseGet(this::createDefaultTenant);

        if (!userRepository.existsByUsernameAndTenantId(DEFAULT_ADMIN_USERNAME, tenant.getId())) {
            String password = resolveInitialPassword();
            createDefaultAdmin(tenant, password);
        } else {
            log.info("Default admin user already exists — skipping seed");
        }
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private String resolveInitialPassword() {
        if (adminInitialPassword != null && !adminInitialPassword.isBlank()) {
            log.warn("Seeding admin user with password from ADMIN_INITIAL_PASSWORD env var. " +
                     "Change this password immediately after first login.");
            return adminInitialPassword;
        }

        // No env var supplied — generate a secure random password and display it ONCE.
        String generated = UUID.randomUUID().toString().replace("-", "")
                         + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        String banner = """

            ╔══════════════════════════════════════════════════════════════════╗
            ║          ROUTIFY — INITIAL ADMIN CREDENTIALS (one-time)         ║
            ║                                                                  ║
            ║  Username : admin                                                ║
            ║  Password : %-52s║
            ║  Tenant   : platform                                             ║
            ║                                                                  ║
            ║  ⚠  You MUST change this password immediately after first login. ║
            ║  Set ADMIN_INITIAL_PASSWORD env var to suppress this message.   ║
            ╚══════════════════════════════════════════════════════════════════╝
            """.formatted(generated + "  ");

        log.warn(banner);
        return generated;
    }

    private Tenant createDefaultTenant() {
        log.info("Creating default platform tenant (slug={})", DEFAULT_TENANT_SLUG);
        return tenantRepository.save(Tenant.builder()
                .name("Platform Admin")
                .slug(DEFAULT_TENANT_SLUG)
                .plan(TenantPlan.ENTERPRISE)
                .contactEmail(DEFAULT_ADMIN_EMAIL)
                .build());
    }

    private void createDefaultAdmin(Tenant tenant, String password) {
        log.info("Creating default admin user (username={}, tenant={})",
                DEFAULT_ADMIN_USERNAME, DEFAULT_TENANT_SLUG);

        AppUser admin = AppUser.builder()
                .tenantId(tenant.getId())
                .username(DEFAULT_ADMIN_USERNAME)
                .email(DEFAULT_ADMIN_EMAIL)
                .passwordHash(passwordEncoder.encode(password))
                .role(UserRole.SUPER_ADMIN)
                .mustChangePassword(true)   // Force password reset on first login
                .build();

        userRepository.save(admin);
        log.info("Default admin user created — password change required on first login");
    }
}
