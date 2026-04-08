package io.routify.cert;

import io.routify.cert.exception.CertVaultEncryptionException;
import io.routify.cert.service.CertEncryptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link CertEncryptionService}.
 *
 * <p>Validates AES-256-GCM encrypt/decrypt round-trip, invalid payload handling,
 * and error classification. Uses the test encryption key configured in
 * {@code application-test.yml} and real PostgreSQL + Kafka + RabbitMQ containers.
 */
class CertEncryptionServiceIT extends CertVaultIntegrationBase {

    @Test
    @DisplayName("Encrypt and decrypt round-trip preserves original plaintext")
    void encryptDecrypt_roundTrip_preservesPlaintext() {
        String plaintext = """
                -----BEGIN CERTIFICATE-----
                MIIBxTCCAWugAwIBAgIJALRiMLAh4bMjMAoGCCqGSM49BAMCMDoxCzAJBgNVBAYT
                AlVTMQswCQYDVQQIDAJOWTEPMA0GA1UEBwwGQWxiYW55MQ0wCwYDVQQKDARUZXN0
                -----END CERTIFICATE-----
                """;

        CertEncryptionService.EncryptedPayload encrypted = encryptionService.encrypt(plaintext);

        assertThat(encrypted.ciphertext()).isNotBlank();
        assertThat(encrypted.iv()).isNotBlank();
        assertThat(encrypted.tag()).isNotBlank();

        String decrypted = encryptionService.decrypt(encrypted);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("Each encryption produces a unique IV (different ciphertext)")
    void encrypt_samePlaintext_producesUniqueIv() {
        String plaintext = "test-certificate-material";

        CertEncryptionService.EncryptedPayload enc1 = encryptionService.encrypt(plaintext);
        CertEncryptionService.EncryptedPayload enc2 = encryptionService.encrypt(plaintext);

        // IVs should differ (12 random bytes each)
        assertThat(enc1.iv()).isNotEqualTo(enc2.iv());
        // Ciphertexts should also differ due to different IVs
        assertThat(enc1.ciphertext()).isNotEqualTo(enc2.ciphertext());

        // But both should decrypt to the same plaintext
        assertThat(encryptionService.decrypt(enc1)).isEqualTo(plaintext);
        assertThat(encryptionService.decrypt(enc2)).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("Decrypt with tampered ciphertext throws DECRYPTION_FAILED")
    void decrypt_tamperedCiphertext_throwsDecryptionFailed() {
        String plaintext = "sensitive-private-key-data";
        CertEncryptionService.EncryptedPayload encrypted = encryptionService.encrypt(plaintext);

        // Tamper with the ciphertext
        String tampered = encrypted.ciphertext().substring(0, encrypted.ciphertext().length() - 4) + "AAAA";
        CertEncryptionService.EncryptedPayload tamperedPayload =
                new CertEncryptionService.EncryptedPayload(tampered, encrypted.iv(), encrypted.tag());

        assertThatThrownBy(() -> encryptionService.decrypt(tamperedPayload))
                .isInstanceOf(CertVaultEncryptionException.class)
                .satisfies(ex -> {
                    CertVaultEncryptionException cve = (CertVaultEncryptionException) ex;
                    assertThat(cve.getReason()).isEqualTo(CertVaultEncryptionException.Reason.DECRYPTION_FAILED);
                });
    }

    @Test
    @DisplayName("Decrypt with invalid Base64 payload throws INVALID_PAYLOAD")
    void decrypt_invalidBase64_throwsInvalidPayload() {
        CertEncryptionService.EncryptedPayload badPayload =
                new CertEncryptionService.EncryptedPayload("not-valid-base64!!!", "AAAA", "BBBB");

        assertThatThrownBy(() -> encryptionService.decrypt(badPayload))
                .isInstanceOf(CertVaultEncryptionException.class)
                .satisfies(ex -> {
                    CertVaultEncryptionException cve = (CertVaultEncryptionException) ex;
                    assertThat(cve.getReason()).isIn(
                            CertVaultEncryptionException.Reason.INVALID_PAYLOAD,
                            CertVaultEncryptionException.Reason.DECRYPTION_FAILED
                    );
                });
    }

    @Test
    @DisplayName("Encrypt empty string works without error")
    void encrypt_emptyString_succeeds() {
        CertEncryptionService.EncryptedPayload encrypted = encryptionService.encrypt("");
        String decrypted = encryptionService.decrypt(encrypted);
        assertThat(decrypted).isEmpty();
    }

    @Test
    @DisplayName("Large payload encrypt/decrypt round-trip succeeds")
    void encryptDecrypt_largePayload_succeeds() {
        // Simulate a large certificate chain (~100KB)
        String largeCert = "-----BEGIN CERTIFICATE-----\n" +
                "A".repeat(100_000) + "\n" +
                "-----END CERTIFICATE-----\n";

        CertEncryptionService.EncryptedPayload encrypted = encryptionService.encrypt(largeCert);
        String decrypted = encryptionService.decrypt(encrypted);
        assertThat(decrypted).isEqualTo(largeCert);
    }
}

