package io.routify.cert.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * ACME account entity — represents a registered account with an ACME CA
 * (Let's Encrypt, ZeroSSL, etc.).
 *
 * <p>The RSA key pair is AES-256-GCM encrypted at rest using the same
 * {@link io.routify.cert.service.CertEncryptionService} as certificate material.
 */
@Entity
@Table(name = "acme_account", schema = "routify_cert")
@Getter
@Setter
@NoArgsConstructor
public class AcmeAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String email;

    @Column(name = "account_url", length = 2048)
    private String accountUrl;

    /** AES-256-GCM encrypted RSA key pair PEM (base64-encoded ciphertext). */
    @Column(name = "key_pair_pem", nullable = false, columnDefinition = "TEXT")
    private String keyPairPem;

    /** Base64-encoded AES-GCM initialization vector for the key pair. */
    @Column(name = "key_pair_iv", nullable = false, length = 64)
    private String keyPairIv;

    /** Base64-encoded AES-GCM authentication tag for the key pair. */
    @Column(name = "key_pair_tag", nullable = false, length = 64)
    private String keyPairTag;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 100)
    private AcmeProvider provider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AcmeAccountStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // ─── Enums ─────────────────────────────────────────────────────────────────

    public enum AcmeProvider {
        LETSENCRYPT,
        ZEROSSSL
    }

    public enum AcmeAccountStatus {
        ACTIVE,
        DEACTIVATED
    }
}

