package gr.routify.identity.domain;

import gr.routify.common.domain.TenantPlan;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Tenant domain entity.
 *
 * <p>A tenant represents an organization using the Routify platform.
 * Each tenant has isolated routes, filters, and users.
 * The tenant's plan determines quota limits.
 */
@Entity
@Table(
    name = "tenant",
    schema = "routify_identity",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_tenant_name", columnNames = "name"),
        @UniqueConstraint(name = "uq_tenant_slug", columnNames = "slug")
    }
)
public class Tenant {

    public enum Status { ACTIVE, SUSPENDED, DELETED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, length = 100, unique = true)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TenantPlan plan;

    @Column(name = "contact_email", length = 255)
    private String contactEmail;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Tenant() {}

    private Tenant(Builder b) {
        this.name         = Objects.requireNonNull(b.name);
        this.slug         = Objects.requireNonNull(b.slug);
        this.status       = Status.ACTIVE;
        this.plan         = b.plan != null ? b.plan : TenantPlan.FREE;
        this.contactEmail = b.contactEmail;
    }

    public void suspend()   { this.status = Status.SUSPENDED; }
    public void reactivate(){ this.status = Status.ACTIVE; }
    public void delete()    { this.status = Status.DELETED; }
    public boolean isActive(){ return status == Status.ACTIVE; }
    public void upgradePlan(TenantPlan newPlan){ this.plan = newPlan; }

    public UUID getId()              { return id; }
    public String getName()          { return name; }
    public String getSlug()          { return slug; }
    public Status getStatus()        { return status; }
    public TenantPlan getPlan()      { return plan; }
    public String getContactEmail()  { return contactEmail; }
    public Instant getCreatedAt()    { return createdAt; }

    public void setName(String name)               { this.name = name; }
    public void setContactEmail(String email)      { this.contactEmail = email; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String name;
        private String slug;
        private TenantPlan plan;
        private String contactEmail;

        public Builder name(String name)              { this.name = name; return this; }
        public Builder slug(String slug)              { this.slug = slug; return this; }
        public Builder plan(TenantPlan plan)          { this.plan = plan; return this; }
        public Builder contactEmail(String email)     { this.contactEmail = email; return this; }
        public Tenant build()                        { return new Tenant(this); }
    }
}

