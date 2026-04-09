package io.routify.gateway.filter;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Shared helper for generating self-signed X.509 certificates in tests.
 * All certificates are generated with BouncyCastle — available on the gateway classpath.
 */
final class TestCertificateHelper {

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private TestCertificateHelper() {}

    /**
     * Generates a self-signed X.509 certificate with the given subject CN valid from
     * {@code notBefore} to {@code notAfter}.
     */
    static X509Certificate generateCert(String cn, Instant notBefore, Instant notAfter) {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", "BC");
            kpg.initialize(2048);
            KeyPair keyPair = kpg.generateKeyPair();

            X500Name issuer = new X500Name("CN=" + cn + ",O=RoutifyTest,C=GR");
            BigInteger serial = BigInteger.valueOf(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));

            JcaX509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                    issuer, serial,
                    Date.from(notBefore), Date.from(notAfter),
                    issuer, keyPair.getPublic());

            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                    .setProvider("BC")
                    .build(keyPair.getPrivate());

            X509CertificateHolder holder = certBuilder.build(signer);
            return new JcaX509CertificateConverter()
                    .setProvider("BC")
                    .getCertificate(holder);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate test certificate", e);
        }
    }

    /** Generates a self-signed cert valid for 365 days from now. */
    static X509Certificate generateCert(String cn) {
        return generateCert(cn, Instant.now().minus(1, ChronoUnit.HOURS),
                Instant.now().plus(365, ChronoUnit.DAYS));
    }

    /** Generates a cert that expired {@code daysAgo} days ago. */
    static X509Certificate generateExpiredCert(String cn, int daysAgo) {
        Instant notBefore = Instant.now().minus(daysAgo + 365L, ChronoUnit.DAYS);
        Instant notAfter = Instant.now().minus(daysAgo, ChronoUnit.DAYS);
        return generateCert(cn, notBefore, notAfter);
    }

    /** Generates a cert that expires in {@code daysFromNow} days. */
    static X509Certificate generateExpiringCert(String cn, int daysFromNow) {
        return generateCert(cn, Instant.now().minus(1, ChronoUnit.HOURS),
                Instant.now().plus(daysFromNow, ChronoUnit.DAYS));
    }

    /** Converts a certificate to PEM-encoded string. */
    static String toPem(X509Certificate cert) {
        try {
            String base64 = Base64.getEncoder().encodeToString(cert.getEncoded());
            return "-----BEGIN CERTIFICATE-----\n" + base64 + "\n-----END CERTIFICATE-----";
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode certificate to PEM", e);
        }
    }
}

