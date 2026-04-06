package io.routify.cert.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.routify.cert.domain.StoredCertificate;
import lombok.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTO representing a stored certificate in the vault.
 * Certificate material (raw PEM/PKCS12 bytes) is NEVER included in responses —
 * only metadata and the ability to download via a separate secure endpoint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CertificateDto {

    private UUID   id;
    private UUID   tenantId;
    private String logicalId;
    private String alias;
    private String description;
    private String format;      // PEM | PKCS12
    private String status;      // ACTIVE | REVOKED | EXPIRED | DELETED
    private String expiryStatus; // VALID | EXPIRING_SOON | EXPIRED  (computed)

    // X.509 metadata
    private String       subjectDn;
    private String       issuerDn;
    private String       serialNumber;
    private Instant      notBefore;
    private Instant      notAfter;
    private String       signatureAlg;
    private String       keyAlgorithm;
    private Integer      keySize;
    private String       fingerprintSha1;
    private String       fingerprintSha256;
    private List<String> sanDns;
    private List<String> sanIp;
    private boolean      isCa;
    private boolean      hasPrivateKey;

    // Gateway mapping
    private String gatewayTlsLogicalId;

    // Group membership
    /** UUID of the group this certificate belongs to (null for standalone certs). */
    private UUID   groupId;
    /** The group's stable logical ID — this is the gateway TLS registry key when grouped. */
    private String groupLogicalId;
    /** Short label distinguishing this cert within its group (e.g. "primary", "backup-2025"). */
    private String memberAlias;
    /**
     * Effective gateway registry key: {@code groupLogicalId} if grouped,
     * otherwise {@code gatewayTlsLogicalId}.
     */
    private String effectiveGatewayLogicalId;

    // Audit
    private String  uploadedBy;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant expiresAt;

    /** Map from entity to DTO (no certificate material). */
    public static CertificateDto from(StoredCertificate c) {
        String grpLogicalId = c.getGroup() != null ? c.getGroup().getLogicalId() : null;
        return CertificateDto.builder()
                .id(c.getId())
                .tenantId(c.getTenantId())
                .logicalId(c.getLogicalId())
                .alias(c.getAlias())
                .description(c.getDescription())
                .format(c.getFormat() != null ? c.getFormat().name() : null)
                .status(c.getStatus() != null ? c.getStatus().name() : null)
                .expiryStatus(c.computeExpiryStatus(30))
                .subjectDn(c.getSubjectDn())
                .issuerDn(c.getIssuerDn())
                .serialNumber(c.getSerialNumber())
                .notBefore(c.getNotBefore())
                .notAfter(c.getNotAfter())
                .signatureAlg(c.getSignatureAlg())
                .keyAlgorithm(c.getKeyAlgorithm())
                .keySize(c.getKeySize())
                .fingerprintSha1(c.getFingerprintSha1())
                .fingerprintSha256(c.getFingerprintSha256())
                .sanDns(c.getSanDns())
                .sanIp(c.getSanIp())
                .isCa(c.isCa())
                .hasPrivateKey(c.getPrivateKeyEnc() != null)
                .gatewayTlsLogicalId(c.getGatewayTlsLogicalId())
                .groupId(c.getGroup() != null ? c.getGroup().getId() : null)
                .groupLogicalId(grpLogicalId)
                .memberAlias(c.getMemberAlias())
                .effectiveGatewayLogicalId(c.effectiveGatewayLogicalId())
                .uploadedBy(c.getUploadedBy())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .expiresAt(c.getExpiresAt())
                .build();
    }
}

