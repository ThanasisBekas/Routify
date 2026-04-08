package io.routify.cert.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * ACME order entity — tracks the lifecycle of a certificate issuance/renewal
 * request through the ACME protocol (HTTP-01 challenge flow).
 *
 * <p>Status transitions:
 * {@code PENDING → VALIDATING → COMPLETED} (happy path)
 * {@code PENDING → VALIDATING → FAILED} (challenge or issuance error)
 * {@code COMPLETED → RENEWAL_FAILED} (auto-renewal failure)
 */
@Entity
@Table(name = "acme_order", schema = "routify_cert")
@Getter
@Setter
@NoArgsConstructor
public class AcmeOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private AcmeAccount account;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String domain;

    @Column(name = "cert_group_id")
    private UUID certGroupId;

    @Enumerated(EnumType.STRING)
    @Column(name = "challenge_type", nullable = false, length = 20)
    private ChallengeType challengeType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AcmeOrderStatus status;

    @Column(name = "order_url", length = 2048)
    private String orderUrl;

    @Column(name = "challenge_token", length = 1024)
    private String challengeToken;

    @Column(name = "challenge_content", columnDefinition = "TEXT")
    private String challengeContent;

    /** The resulting certificate ID after successful issuance. */
    @Column(name = "cert_id")
    private UUID certId;

    @Column(name = "auto_renew", nullable = false)
    private boolean autoRenew;

    @Column(name = "last_renewed_at")
    private Instant lastRenewedAt;

    @Column(name = "next_renewal_at")
    private Instant nextRenewalAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    // ─── Enums ─────────────────────────────────────────────────────────────────

    public enum ChallengeType {
        HTTP_01
    }

    public enum AcmeOrderStatus {
        PENDING,
        VALIDATING,
        COMPLETED,
        FAILED,
        RENEWAL_FAILED
    }
}

