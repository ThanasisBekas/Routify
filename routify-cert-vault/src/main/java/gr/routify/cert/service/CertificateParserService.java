package gr.routify.cert.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;

/**
 * Parses PEM/PKCS12 certificate data and extracts X.509 metadata.
 * Used at upload time to populate the metadata columns of {@link gr.routify.cert.domain.StoredCertificate}.
 */
@Slf4j
@Service
public class CertificateParserService {

    /**
     * Parse the first X.509 certificate from a PEM-encoded chain and extract metadata.
     *
     * @param pemData PEM-encoded certificate chain (may contain multiple certs)
     * @return parsed metadata, or a metadata object with only the rawData set if parsing fails
     */
    public CertMetadata parsePem(String pemData) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            byte[] bytes = pemData.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Collection<? extends java.security.cert.Certificate> certs =
                    cf.generateCertificates(new ByteArrayInputStream(bytes));

            if (certs.isEmpty()) {
                log.warn("No certificates found in PEM data");
                return CertMetadata.empty();
            }

            X509Certificate leaf = (X509Certificate) certs.iterator().next();
            return extractMetadata(leaf);
        } catch (Exception e) {
            log.warn("Failed to parse PEM certificate, storing without metadata: {}", e.getMessage());
            return CertMetadata.empty();
        }
    }

    private CertMetadata extractMetadata(X509Certificate cert) {
        try {
            String sha1   = fingerprint(cert.getEncoded(), "SHA-1");
            String sha256 = fingerprint(cert.getEncoded(), "SHA-256");

            List<String> sanDns = new ArrayList<>();
            List<String> sanIp  = new ArrayList<>();
            try {
                Collection<List<?>> sans = cert.getSubjectAlternativeNames();
                if (sans != null) {
                    for (List<?> san : sans) {
                        int type = (Integer) san.get(0);
                        String value = san.get(1).toString();
                        if (type == 2) sanDns.add(value);      // DNS
                        else if (type == 7) sanIp.add(value);  // IP
                    }
                }
            } catch (Exception ignored) {}

            // Basic constraints — isCa if pathLen constraint exists or isCA flag set
            boolean isCa = false;
            try {
                int bc = cert.getBasicConstraints();
                isCa = bc >= 0; // >= 0 means it's a CA cert
            } catch (Exception ignored) {}

            // Key size heuristic
            Integer keySize = null;
            try {
                java.security.PublicKey pk = cert.getPublicKey();
                if (pk instanceof java.security.interfaces.RSAPublicKey rsa) {
                    keySize = rsa.getModulus().bitLength();
                } else if (pk instanceof java.security.interfaces.ECPublicKey ec) {
                    keySize = ec.getParams().getCurve().getField().getFieldSize();
                }
            } catch (Exception ignored) {}

            return new CertMetadata(
                    cert.getSubjectX500Principal().getName(),
                    cert.getIssuerX500Principal().getName(),
                    cert.getSerialNumber().toString(16).toUpperCase(),
                    cert.getNotBefore().toInstant(),
                    cert.getNotAfter().toInstant(),
                    cert.getSigAlgName(),
                    cert.getPublicKey().getAlgorithm(),
                    keySize,
                    sha1,
                    sha256,
                    sanDns.isEmpty() ? null : sanDns,
                    sanIp.isEmpty()  ? null : sanIp,
                    isCa
            );
        } catch (Exception e) {
            log.warn("Failed to extract full certificate metadata: {}", e.getMessage());
            return CertMetadata.empty();
        }
    }

    private String fingerprint(byte[] encoded, String algorithm) throws Exception {
        MessageDigest md = MessageDigest.getInstance(algorithm);
        byte[] digest = md.digest(encoded);
        return HexFormat.of().withUpperCase().withDelimiter(":").formatHex(digest);
    }

    /**
     * Certificate metadata extracted from an X.509 certificate.
     */
    public record CertMetadata(
            String subjectDn,
            String issuerDn,
            String serialNumber,
            java.time.Instant notBefore,
            java.time.Instant notAfter,
            String signatureAlg,
            String keyAlgorithm,
            Integer keySize,
            String fingerprintSha1,
            String fingerprintSha256,
            List<String> sanDns,
            List<String> sanIp,
            boolean isCa
    ) {
        public static CertMetadata empty() {
            return new CertMetadata(null, null, null, null, null, null, null, null, null, null, null, null, false);
        }

        public boolean isEmpty() {
            return subjectDn == null && notAfter == null;
        }
    }
}

