package gr.routify.gateway.certificate;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMEncryptedKeyPair;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder;
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Stateless utility for parsing PEM-encoded X.509 certificates and private keys
 * from strings, files, and HTTP headers. Requires BouncyCastle on the classpath.
 */
@Slf4j
public final class PemCertificateParser {

    private static final String BEGIN_CERT = "-----BEGIN CERTIFICATE-----";
    private static final String END_CERT = "-----END CERTIFICATE-----";

    private PemCertificateParser() {}

    /** Parses one or more X.509 certificates from a PEM string. */
    public static List<X509Certificate> parseCertificates(String pemContent) {
        Objects.requireNonNull(pemContent, "PEM content must not be null");
        try {
            var factory = CertificateFactory.getInstance("X.509", "BC");
            var is = new ByteArrayInputStream(pemContent.getBytes(StandardCharsets.UTF_8));
            @SuppressWarnings("unchecked")
            var certs = (Collection<X509Certificate>) factory.generateCertificates(is);
            if (certs.isEmpty()) throw new CertificateLoadingException("No certificates found in PEM content");
            var result = new ArrayList<>(certs);
            result.forEach(c -> log.debug("Parsed certificate: subject={}, notAfter={}",
                    c.getSubjectX500Principal().getName(), c.getNotAfter()));
            return result;
        } catch (CertificateLoadingException e) {
            throw e;
        } catch (Exception e) {
            throw new CertificateLoadingException("Failed to parse X.509 certificates from PEM", e);
        }
    }

    /** Parses certificates from a classpath or filesystem path. */
    public static List<X509Certificate> parseCertificatesFromPath(String path) {
        Objects.requireNonNull(path, "Certificate path must not be null");
        try (var is = resolveInputStream(path)) {
            var factory = CertificateFactory.getInstance("X.509", "BC");
            @SuppressWarnings("unchecked")
            var certs = (Collection<X509Certificate>) factory.generateCertificates(is);
            if (certs.isEmpty()) throw new CertificateLoadingException("No certificates found at path: " + path);
            return new ArrayList<>(certs);
        } catch (CertificateLoadingException e) {
            throw e;
        } catch (Exception e) {
            throw new CertificateLoadingException("Failed to parse certificates from path: " + path, e);
        }
    }

    /**
     * Parses a single certificate from a PEM string, tolerating missing or
     * malformed markers (e.g. from HTTP headers).
     */
    public static X509Certificate parseCertificateFromHeader(String rawPem) {
        if (rawPem == null || rawPem.isBlank()) return null;
        var normalized = ensurePemMarkers(rawPem);
        try {
            var factory = CertificateFactory.getInstance("X.509", "BC");
            var is = new ByteArrayInputStream(normalized.getBytes(StandardCharsets.UTF_8));
            return (X509Certificate) factory.generateCertificate(is);
        } catch (Exception e) {
            log.warn("Failed to parse certificate from header value", e);
            return null;
        }
    }

    /**
     * Parses a private key from PEM.
     * Supports PKCS8 encrypted, PEM encrypted, and plain formats.
     */
    public static PrivateKey parsePrivateKey(String pem, char[] password) {
        Objects.requireNonNull(pem, "Private key PEM must not be null");
        try {
            var keyInfo = resolvePrivateKeyInfo(pem, password);
            if (keyInfo == null) throw new CertificateLoadingException("No private key found in PEM content");
            // Derive the algorithm from the key's AlgorithmIdentifier so that RSA, EC, DSA, etc.
            // all work without hard-coding "RSA".
            String algorithm = resolveKeyAlgorithm(keyInfo);
            var keyFactory = KeyFactory.getInstance(algorithm, "BC");
            var privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(keyInfo.getEncoded()));
            log.debug("Parsed {} private key", privateKey.getAlgorithm());
            return privateKey;
        } catch (CertificateLoadingException e) {
            throw e;
        } catch (Exception e) {
            throw new CertificateLoadingException("Failed to parse private key from PEM", e);
        }
    }

    /**
     * Maps the OID from a {@link PrivateKeyInfo}'s AlgorithmIdentifier to a JCA algorithm name.
     * Falls back to "RSA" for unknown OIDs to preserve backward compatibility.
     */
    private static String resolveKeyAlgorithm(PrivateKeyInfo keyInfo) {
        String oid = keyInfo.getPrivateKeyAlgorithm().getAlgorithm().getId();
        // RSA (PKCS#1 / PKCS#8)
        if ("1.2.840.113549.1.1.1".equals(oid)) return "RSA";
        // EC
        if ("1.2.840.10045.2.1".equals(oid)) return "EC";
        // DSA
        if ("1.2.840.10040.4.1".equals(oid)) return "DSA";
        // Ed25519 / Ed448 (RFC 8410)
        if ("1.3.101.112".equals(oid)) return "Ed25519";
        if ("1.3.101.113".equals(oid)) return "Ed448";
        log.warn("Unknown private key OID '{}' — falling back to RSA", oid);
        return "RSA";
    }

    private static PrivateKeyInfo resolvePrivateKeyInfo(String pem, char[] password) throws Exception {
        try (var parser = new PEMParser(new StringReader(pem))) {
            var parsed = parser.readObject();
            return switch (parsed) {
                case PKCS8EncryptedPrivateKeyInfo encrypted -> {
                    Objects.requireNonNull(password, "Password required for encrypted PKCS8 key");
                    var decryptor = new JceOpenSSLPKCS8DecryptorProviderBuilder().build(password);
                    yield encrypted.decryptPrivateKeyInfo(decryptor);
                }
                case PEMEncryptedKeyPair encrypted -> {
                    Objects.requireNonNull(password, "Password required for encrypted PEM key pair");
                    var decryptor = new JcePEMDecryptorProviderBuilder().build(password);
                    yield encrypted.decryptKeyPair(decryptor).getPrivateKeyInfo();
                }
                case PEMKeyPair keyPair -> keyPair.getPrivateKeyInfo();
                case null -> null;
                default -> {
                    log.warn("Unsupported private key format: {}", parsed.getClass().getName());
                    yield null;
                }
            };
        }
    }

    /**
     * Normalises a raw PEM string into a well-formed PEM that BouncyCastle can parse.
     */
    static String ensurePemMarkers(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        String base64 = raw
                .replace(BEGIN_CERT, "")
                .replace(END_CERT, "")
                .replaceAll("\\s+", "");
        return BEGIN_CERT + "\n" + base64 + "\n" + END_CERT;
    }

    private static InputStream resolveInputStream(String path) {
        var is = PemCertificateParser.class.getClassLoader().getResourceAsStream(path);
        if (is != null) return is;
        try {
            return new FileInputStream(path);
        } catch (FileNotFoundException e) {
            throw new CertificateLoadingException("Certificate file not found: " + path, e);
        }
    }
}

