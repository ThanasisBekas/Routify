package io.routify.identity.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.*;

/**
 * Role definition entity — represents a built-in or custom role with
 * an associated set of permissions.
 *
 * <p>Built-in roles ({@code builtIn=true}) have {@code tenantId=null} and
 * well-known UUIDs. Custom roles are tenant-scoped and created by TENANT_ADMIN+.
 */
@Entity
@Table(
    name = "role_definition",
    schema = "routify_identity",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_role_tenant_name", columnNames = {"tenant_id", "name"})
    }
)
public class RoleDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(name = "built_in", nullable = false)
    private boolean builtIn;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "role_permission",
        schema = "routify_identity",
        joinColumns = @JoinColumn(name = "role_id")
    )
    @Column(name = "permission", length = 100)
    private Set<String> permissions = new HashSet<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RoleDefinition() {}

    private RoleDefinition(Builder b) {
        this.tenantId    = b.tenantId;
        this.name        = Objects.requireNonNull(b.name);
        this.description = b.description;
        this.builtIn     = b.builtIn;
        this.permissions = b.permissions != null ? new HashSet<>(b.permissions) : new HashSet<>();
    }

    // ─── Getters ──────────────────────────────────────────────────────────────

    public UUID getId()                   { return id; }
    public UUID getTenantId()             { return tenantId; }
    public String getName()               { return name; }
    public String getDescription()        { return description; }
    public boolean isBuiltIn()            { return builtIn; }
    public Set<String> getPermissions()   { return Collections.unmodifiableSet(permissions); }
    public Instant getCreatedAt()         { return createdAt; }

    // ─── Setters (for mutable operations) ─────────────────────────────────────

    public void setName(String name)                { this.name = name; }
    public void setDescription(String description)  { this.description = description; }

    public void setPermissions(Set<String> perms) {
        this.permissions.clear();
        if (perms != null) {
            this.permissions.addAll(perms);
        }
    }

    // ─── Builder ──────────────────────────────────────────────────────────────

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private UUID tenantId;
        private String name;
        private String description;
        private boolean builtIn;
        private Set<String> permissions;

        public Builder tenantId(UUID tenantId)              { this.tenantId = tenantId; return this; }
        public Builder name(String name)                    { this.name = name; return this; }
        public Builder description(String description)      { this.description = description; return this; }
        public Builder builtIn(boolean builtIn)              { this.builtIn = builtIn; return this; }
        public Builder permissions(Set<String> permissions)  { this.permissions = permissions; return this; }
        public RoleDefinition build()                        { return new RoleDefinition(this); }
    }
}

