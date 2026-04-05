package io.routify.cert.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Certificate Group — a logical container that groups one or more {@link StoredCertificate}s
 * under a single stable {@code logicalId}.
 *
 * <h3>Purpose</h3>
 * <ul>
 *   <li>The group's {@code logicalId} is the key used by the gateway TLS registry and the
 *       {@code CertRotation} / {@code CertVaultAuth} filters. It never changes even when
 *       individual certificates are rotated.</li>
 *   <li>Multiple certificates may belong to the same group (primary + backup, RSA + ECDSA, etc.).</li>
 *   <li>Certificate rotation is achieved by adding the new cert as a member, then removing the old one.</li>
 *   <li>Authorization filters bind to a group logical ID, not to individual cert IDs.</li>
 * </ul>
 */
@Entity
@Table(
    name = "cert_group",
    schema = "routify_cert",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_cert_group_logical_tenant", columnNames = {"logical_id", "tenant_id"}),
        @UniqueConstraint(name = "uq_cert_group_alias_tenant",   columnNames = {"alias",      "tenant_id"})
    }
)
@Getter
@Setter
@NoArgsConstructor
public class CertGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /**
     * Stable logical identifier — the gateway TLS registry key and filter binding key.
     * URL-safe, unique per tenant. E.g. {@code "my-api-inbound-tls"}.
     */
    @Column(name = "logical_id", nullable = false, length = 100)
    private String logicalId;

    /** Human-readable display name */
    @Column(nullable = false, length = 255)
    private String alias;

    @Column(length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GroupStatus status = GroupStatus.ACTIVE;

    // ─── Members ─────────────────────────────────────────────────────────────

    @OneToMany(mappedBy = "group", fetch = FetchType.LAZY)
    private List<StoredCertificate> members = new ArrayList<>();

    // ─── Audit ───────────────────────────────────────────────────────────────

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    // ─── Domain helpers ───────────────────────────────────────────────────────

    public void archive() {
        this.status = GroupStatus.ARCHIVED;
    }

    public boolean isActive() {
        return this.status == GroupStatus.ACTIVE;
    }

    // ─── Enums ────────────────────────────────────────────────────────────────

    public enum GroupStatus {
        ACTIVE, ARCHIVED
    }
}

