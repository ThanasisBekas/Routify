package gr.routify.cert.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Core domain entity — an uploaded inbound TLS certificate stored in the vault.
 *
 * <p>Certificate material (PEM chain + optional private key) is stored AES-256-GCM
 * encrypted. X.509 metadata (subject, expiry, SANs, etc.) is extracted at upload
 * time and stored in plain columns for querying and expiry alerting.
 *
 * <p>The {@code logicalId} is the identifier that gateway TLS configuration
 * references to map this certificate to an inbound TLS listener.
 */
@Entity
@Table(
    name = "stored_certificate",
    schema = "routify_cert",
    uniqueConstraints = {
        // NOTE: uq_cert_logical_tenant was dropped in V3 migration — logicalId is now auto-generated
        @UniqueConstraint(name = "uq_cert_alias_tenant", columnNames = {"alias", "tenant_id"})
    }
)
@Getter
@Setter
@NoArgsConstructor
public class StoredCertificate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /**
     * Internal identifier — auto-generated as {@code "cert-<uuid>"} for new uploads.
     * No longer the gateway TLS registry key (that role belongs to the group's {@code logicalId}).
     * Nullable for new uploads; kept for backwards compatibility with legacy certs.
     */
    @Column(name = "logical_id", nullable = true, length = 100)
    private String logicalId;

    /** Human-readable alias / display name */
    @Column(nullable = false, length = 255)
    private String alias;

    @Column(length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CertFormat format;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CertStatus status;

    // ─── X.509 Metadata ────────────────────────────────────────────────────────

    @Column(name = "subject_dn", length = 500)
    private String subjectDn;

    @Column(name = "issuer_dn", length = 500)
    private String issuerDn;

    @Column(name = "serial_number", length = 100)
    private String serialNumber;

    @Column(name = "not_before")
    private Instant notBefore;

    @Column(name = "not_after")
    private Instant notAfter;

    @Column(name = "signature_alg", length = 50)
    private String signatureAlg;

    @Column(name = "key_algorithm", length = 50)
    private String keyAlgorithm;

    @Column(name = "key_size")
    private Integer keySize;

    @Column(name = "fingerprint_sha1", length = 64)
    private String fingerprintSha1;

    @Column(name = "fingerprint_sha256", length = 128)
    private String fingerprintSha256;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "san_dns", columnDefinition = "TEXT[]")
    private List<String> sanDns;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "san_ip", columnDefinition = "TEXT[]")
    private List<String> sanIp;

    @Column(name = "is_ca", nullable = false)
    private boolean isCa;

    // ─── Encrypted Certificate Material ────────────────────────────────────────

    /** AES-256-GCM encrypted certificate chain (base64-encoded ciphertext) */
    @Column(name = "cert_data_enc", nullable = false, columnDefinition = "TEXT")
    private String certDataEnc;

    /** AES-256-GCM encrypted private key (base64-encoded ciphertext), nullable */
    @Column(name = "private_key_enc", columnDefinition = "TEXT")
    private String privateKeyEnc;

    /** Base64-encoded AES-GCM initialization vector */
    @Column(name = "enc_iv", nullable = false, length = 64)
    private String encIv;

    /** Base64-encoded AES-GCM authentication tag */
    @Column(name = "enc_tag", nullable = false, length = 64)
    private String encTag;

    // ─── Certificate Group ────────────────────────────────────────────────────

    /**
     * The group this certificate belongs to.
     * When a group is set the gateway TLS registry key becomes the group's {@code logicalId},
     * enabling seamless rotation and multi-cert grouping.
     * Null for ungrouped (standalone) certificates.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id")
    private CertGroup group;

    /**
     * Short label distinguishing this certificate within its group.
     * E.g. "primary", "backup-2025", "ecdsa-leaf".
     * Must be unique within the group.
     */
    @Column(name = "member_alias", length = 100)
    private String memberAlias;

    // ─── Gateway Reference ─────────────────────────────────────────────────────

    /**
     * The gateway TLS logical ID this certificate is mapped to (standalone / legacy binding).
     * When the certificate belongs to a group, the group's {@code logicalId} is used as
     * the gateway registry key and this field is ignored by the gateway.
     * Null if not yet assigned to any gateway TLS entry.
     */
    @Column(name = "gateway_tls_logical_id", length = 100)
    private String gatewayTlsLogicalId;

    /**
     * Canonical gateway TLS registry key — the single source of truth replacing the
     * ambiguous triplet ({@code logicalId}, {@code gatewayTlsLogicalId}, {@code group.logicalId}).
     *
     * <p>Maintained by database trigger {@code trg_cert_effective_logical_id} (V2 migration):
     * <ul>
     *   <li>If {@code group_id IS NOT NULL} → equals {@code cert_group.logical_id}</li>
     *   <li>Otherwise → equals {@code gateway_tls_logical_id}</li>
     * </ul>
     *
     * <p>Java application code should use this field instead of calling
     * {@link #effectiveGatewayLogicalId()} wherever possible, as it avoids an extra join.
     * The domain method is retained for cases where the group entity is already loaded.
     */
    @Column(name = "effective_logical_id", length = 100)
    private String effectiveLogicalId;

    /**
     * Returns the effective gateway TLS logical ID used by the registry.
     * Prefer reading {@link #effectiveLogicalId} directly when it is already populated
     * (V2 migration backfill). This method is retained for backwards compatibility and
     * for cases where the {@code group} association is already in the Hibernate session.
     *
     * @deprecated Prefer reading {@code effectiveLogicalId} column directly.
     *             This method requires the {@code group} association to be loaded.
     */
    @Deprecated(forRemoval = false)
    public String effectiveGatewayLogicalId() {
        if (group != null) return group.getLogicalId();
        return gatewayTlsLogicalId;
    }

    // ─── Audit ─────────────────────────────────────────────────────────────────

    @Column(name = "uploaded_by", length = 255)
    private String uploadedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    /** Convenience column — equals notAfter, indexed for expiry queries */
    @Column(name = "expires_at")
    private Instant expiresAt;

    // ─── Domain helpers ────────────────────────────────────────────────────────

    public void revoke() {
        this.status = CertStatus.REVOKED;
    }

    public void markDeleted() {
        this.status = CertStatus.DELETED;
    }

    public boolean isExpired() {
        return notAfter != null && Instant.now().isAfter(notAfter);
    }

    public boolean isExpiringSoon(int warningDays) {
        if (notAfter == null) return false;
        return Instant.now().isAfter(notAfter.minusSeconds((long) warningDays * 86400));
    }

    public String computeExpiryStatus(int warningDays) {
        if (isExpired()) return "EXPIRED";
        if (isExpiringSoon(warningDays)) return "EXPIRING_SOON";
        return "VALID";
    }

    // ─── Enums ─────────────────────────────────────────────────────────────────

    public enum CertFormat {
        PEM, PKCS12
    }

    public enum CertStatus {
        ACTIVE, REVOKED, EXPIRED, DELETED
    }
}

