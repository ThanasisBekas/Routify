package io.routify.identity.domain;

import io.routify.common.domain.UserRole;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Platform user entity.
 *
 * <p>Every user belongs to a tenant (except SUPER_ADMIN users who have tenantId=null).
 * Passwords are stored as BCrypt hashes.
 */
@Entity
@Table(
    name = "app_user",
    schema = "routify_identity",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_user_email_tenant", columnNames = {"email", "tenant_id"}),
        @UniqueConstraint(name = "uq_user_username_tenant", columnNames = {"username", "tenant_id"})
    }
)
public class AppUser {

    public enum Status { ACTIVE, LOCKED, DELETED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(nullable = false, length = 100)
    private String username;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id", nullable = false)
    private RoleDefinition roleDefinition;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "failed_login_attempts", nullable = false)
    private Integer failedLoginAttempts;

    /**
     * When {@code true} the user must change their password before they can
     * perform any other action. Set to {@code true} by the DataSeeder for
     * the auto-generated admin account, and after any admin-initiated
     * password reset.
     */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    protected AppUser() {}

    private AppUser(Builder b) {
        this.tenantId             = b.tenantId;
        this.username             = Objects.requireNonNull(b.username);
        this.email                = Objects.requireNonNull(b.email);
        this.passwordHash         = Objects.requireNonNull(b.passwordHash);
        this.role                 = b.role != null ? b.role : UserRole.VIEWER;
        this.roleDefinition       = b.roleDefinition;
        this.status               = Status.ACTIVE;
        this.failedLoginAttempts  = 0;
        this.mustChangePassword   = b.mustChangePassword;
    }

    // ─── Domain Behaviour ─────────────────────────────────────────────────────

    public void recordSuccessfulLogin() {
        this.lastLoginAt         = Instant.now();
        this.failedLoginAttempts = 0;
        this.lockedUntil         = null;
        // Note: mustChangePassword is cleared explicitly via changePassword(), not here.
    }

    public void recordFailedLogin() {
        this.failedLoginAttempts++;
        if (this.failedLoginAttempts >= 5) {
            this.lockedUntil = Instant.now().plusSeconds(300); // 5-minute lockout
        }
    }

    public boolean isLocked() {
        return lockedUntil != null && Instant.now().isBefore(lockedUntil);
    }

    public boolean isActive() { return status == Status.ACTIVE && !isLocked(); }

    // ─── Getters / Setters ────────────────────────────────────────────────────

    public UUID getId()                  { return id; }
    public UUID getTenantId()            { return tenantId; }
    public String getUsername()          { return username; }
    public String getEmail()             { return email; }
    public String getPasswordHash()      { return passwordHash; }
    public UserRole getRole()            { return role; }
    public RoleDefinition getRoleDefinition() { return roleDefinition; }
    public Status getStatus()            { return status; }
    public Instant getLastLoginAt()      { return lastLoginAt; }
    public Instant getCreatedAt()        { return createdAt; }
    public boolean isMustChangePassword(){ return mustChangePassword; }

    public void setUsername(String u)    { this.username = u; }
    public void setEmail(String e)       { this.email = e; }
    public void setRole(UserRole r)      { this.role = r; }
    public void setRoleDefinition(RoleDefinition rd) { this.roleDefinition = rd; }
    public void lock()                   { this.status = Status.LOCKED; }
    public void unlock()                 { this.status = Status.ACTIVE; this.lockedUntil = null; this.failedLoginAttempts = 0; }
    public void delete()                 { this.status = Status.DELETED; }

    /**
     * Changes the user's password and clears the must-change-password flag.
     * Always use this method (never {@code setPasswordHash} directly) so the
     * flag is guaranteed to be cleared atomically with the hash update.
     */
    public void changePassword(String newPasswordHash) {
        this.passwordHash       = Objects.requireNonNull(newPasswordHash);
        this.mustChangePassword = false;
    }

    /** Admin-initiated reset: set a new hash AND require change on next login. */
    public void adminResetPassword(String temporaryPasswordHash) {
        this.passwordHash       = Objects.requireNonNull(temporaryPasswordHash);
        this.mustChangePassword = true;
    }

    public static Builder builder()      { return new Builder(); }

    public static final class Builder {
        private UUID tenantId;
        private String username;
        private String email;
        private String passwordHash;
        private UserRole role;
        private RoleDefinition roleDefinition;
        private boolean mustChangePassword = false;

        public Builder tenantId(UUID tenantId)                      { this.tenantId = tenantId; return this; }
        public Builder username(String username)                    { this.username = username; return this; }
        public Builder email(String email)                          { this.email = email; return this; }
        public Builder passwordHash(String hash)                    { this.passwordHash = hash; return this; }
        public Builder role(UserRole role)                          { this.role = role; return this; }
        public Builder roleDefinition(RoleDefinition rd)            { this.roleDefinition = rd; return this; }
        public Builder mustChangePassword(boolean mustChange)       { this.mustChangePassword = mustChange; return this; }
        public AppUser build()                                      { return new AppUser(this); }
    }
}

