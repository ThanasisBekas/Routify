package io.routify.gateway.certificate;

import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

/**
 * Immutable snapshot of a certificate registered under a logical ID.
 * Carries version, SHA-256 fingerprint, validity window, optional private key, and load source.
 */
public record VersionedCertificate(
        String id,
        int version,
        X509Certificate certificate,
        PrivateKey privateKey,
        String fingerprint,
        Instant notBefore,
        Instant notAfter,
        Instant loadedAt,
        String source
) {

    /**
     * Constructs an entry, computing fingerprint and validity dates from the certificate.
     */
    public static VersionedCertificate of(String id, int version, X509Certificate certificate,
                                           PrivateKey privateKey, String source) {
        return new VersionedCertificate(
                id, version, certificate, privateKey,
                computeFingerprint(certificate),
                certificate.getNotBefore().toInstant(),
                certificate.getNotAfter().toInstant(),
                Instant.now(),
                source
        );
    }

    /** True if the certificate has passed its notAfter date. */
    public boolean isExpired() {
        return Instant.now().isAfter(notAfter);
    }

    /** Days remaining until expiry; negative if already expired. */
    public long daysUntilExpiry() {
        return ChronoUnit.DAYS.between(Instant.now(), notAfter);
    }

    @Override
    public String toString() {
        return "VersionedCertificate[id=%s, v%d, fp=%s, expires=%s, source=%s]"
                .formatted(id, version, fingerprint, notAfter, source);
    }

    private static String computeFingerprint(X509Certificate cert) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(cert.getEncoded());
            return HexFormat.of().withDelimiter(":").formatHex(hash);
        } catch (Exception e) {
            return "unknown";
        }
    }
}

