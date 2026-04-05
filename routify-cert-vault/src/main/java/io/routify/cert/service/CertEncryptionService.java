package io.routify.cert.service;

import io.routify.cert.exception.CertVaultEncryptionException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-GCM encryption service for certificate material.
 *
 * <p>Certificate PEM data and private keys are encrypted at rest using AES-256-GCM
 * with a fresh random 12-byte IV per encryption operation.
 *
 * <h2>C8 fixes applied</h2>
 * <ol>
 *   <li><b>No key padding</b> — the old implementation zero-padded short keys to 32 bytes,
 *       which dramatically weakens the effective key entropy.  This version requires exactly
 *       32 decoded bytes (256 bits) and fails fast at startup with a clear error if the key
 *       is absent, too short, or too long.</li>
 *   <li><b>Fail-fast at {@code @PostConstruct}</b> — key validation runs during bean
 *       initialisation before the application context finishes loading.  Any misconfiguration
 *       surfaces immediately rather than at runtime on the first encrypt/decrypt call.</li>
 *   <li><b>Typed exceptions</b> — generic {@code RuntimeException} replaced with
 *       {@link CertVaultEncryptionException} using distinct {@link CertVaultEncryptionException.Reason}
 *       values so callers can distinguish key errors from cipher errors from payload errors.</li>
 *   <li><b>Specific catch clauses</b> — catches {@link BadPaddingException} (GCM auth tag
 *       mismatch — indicates tampering or wrong key) and {@link IllegalBlockSizeException}
 *       separately from the generic {@link GeneralSecurityException} fallback.</li>
 * </ol>
 *
 * <h2>Key requirements</h2>
 * <p>The encryption key must be supplied via the {@code CERT_VAULT_ENCRYPTION_KEY}
 * environment variable (mapped to {@code routify.cert-vault.encryption-key}).
 * It must be a Base64-encoded string that decodes to <strong>exactly 32 bytes</strong>.
 *
 * <p>Generate a valid key with: {@code openssl rand -base64 32}
 */
@Slf4j
@Service
public class CertEncryptionService {

    private static final String ALGORITHM    = "AES/GCM/NoPadding";
    private static final int    GCM_TAG_BITS = 128;
    private static final int    IV_BYTES     = 12;
    /** Required decoded key length in bytes for AES-256. */
    private static final int    REQUIRED_KEY_BYTES = 32;

    private final SecretKeySpec secretKey;
    private final SecureRandom  random = new SecureRandom();

    /**
     * Constructs the service and validates the key immediately.
     *
     * @param base64Key Base64-encoded 32-byte AES-256 key from
     *                  {@code routify.cert-vault.encryption-key}
     * @throws CertVaultEncryptionException if the key is missing, too short, or too long
     */
    public CertEncryptionService(
            @Value("${routify.cert-vault.encryption-key}") String base64Key) {
        this.secretKey = validateAndBuildKey(base64Key);
    }

    /**
     * Called by Spring after the bean is fully constructed.
     * Performs an in-memory encrypt/decrypt round-trip to verify the key works correctly.
     * Fails fast with a clear error before the application context finishes loading.
     */
    @PostConstruct
    void validateKeyRoundTrip() {
        try {
            String probe = "routify-key-validation-probe";
            EncryptedPayload ct = encrypt(probe);
            String recovered = decrypt(ct);
            if (!probe.equals(recovered)) {
                throw CertVaultEncryptionException.invalidKey(
                        "AES-256-GCM round-trip validation failed — " +
                        "recovered plaintext does not match probe. " +
                        "Check CERT_VAULT_ENCRYPTION_KEY.");
            }
            log.info("CertEncryptionService: AES-256-GCM key validated successfully");
        } catch (CertVaultEncryptionException e) {
            throw e; // already typed — rethrow as-is
        } catch (Exception e) {
            throw CertVaultEncryptionException.invalidKey(
                    "AES-256-GCM key round-trip validation failed: " + e.getMessage());
        }
    }

    /**
     * Encrypts the given plaintext (certificate PEM or private key material).
     *
     * @param plaintext UTF-8 plaintext to encrypt — must not be null or blank
     * @return {@link EncryptedPayload} containing Base64-encoded ciphertext, IV, and auth tag
     * @throws CertVaultEncryptionException with reason {@code ENCRYPTION_FAILED} on crypto error
     */
    public EncryptedPayload encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));

            byte[] cipherWithTag = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // GCM appends the 16-byte authentication tag at the end of the output
            int cipherLen  = cipherWithTag.length - (GCM_TAG_BITS / 8);
            byte[] cipherBytes = Arrays.copyOf(cipherWithTag, cipherLen);
            byte[] tagBytes    = Arrays.copyOfRange(cipherWithTag, cipherLen, cipherWithTag.length);

            return new EncryptedPayload(
                    Base64.getEncoder().encodeToString(cipherBytes),
                    Base64.getEncoder().encodeToString(iv),
                    Base64.getEncoder().encodeToString(tagBytes)
            );
        } catch (BadPaddingException e) {
            throw CertVaultEncryptionException.encryptionFailed(e);
        } catch (IllegalBlockSizeException e) {
            throw CertVaultEncryptionException.encryptionFailed(e);
        } catch (GeneralSecurityException e) {
            throw CertVaultEncryptionException.encryptionFailed(e);
        }
    }

    /**
     * Decrypts a previously encrypted payload back to its original plaintext.
     *
     * @param payload the {@link EncryptedPayload} returned by {@link #encrypt(String)}
     * @return original plaintext as a UTF-8 string
     * @throws CertVaultEncryptionException with reason {@code DECRYPTION_FAILED} if the
     *         authentication tag does not match (data tampering or wrong key), or
     *         {@code INVALID_PAYLOAD} if the payload fields are malformed Base64
     */
    public String decrypt(EncryptedPayload payload) {
        try {
            byte[] iv          = Base64.getDecoder().decode(payload.iv());
            byte[] cipherBytes = Base64.getDecoder().decode(payload.ciphertext());
            byte[] tagBytes    = Base64.getDecoder().decode(payload.tag());

            if (iv.length != IV_BYTES) {
                throw CertVaultEncryptionException.invalidPayload(
                        "IV must be " + IV_BYTES + " bytes but was " + iv.length);
            }
            if (tagBytes.length != GCM_TAG_BITS / 8) {
                throw CertVaultEncryptionException.invalidPayload(
                        "GCM auth tag must be " + (GCM_TAG_BITS / 8) +
                        " bytes but was " + tagBytes.length);
            }

            // Reconstruct ciphertext + tag as expected by JCE GCM
            byte[] cipherWithTag = new byte[cipherBytes.length + tagBytes.length];
            System.arraycopy(cipherBytes, 0, cipherWithTag, 0, cipherBytes.length);
            System.arraycopy(tagBytes, 0, cipherWithTag, cipherBytes.length, tagBytes.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plainBytes = cipher.doFinal(cipherWithTag);

            return new String(plainBytes, StandardCharsets.UTF_8);

        } catch (CertVaultEncryptionException e) {
            throw e; // already typed — rethrow as-is
        } catch (BadPaddingException e) {
            // GCM authentication tag failure — most likely wrong key or tampered data
            throw CertVaultEncryptionException.decryptionFailed(e);
        } catch (IllegalBlockSizeException e) {
            throw CertVaultEncryptionException.decryptionFailed(e);
        } catch (GeneralSecurityException e) {
            throw CertVaultEncryptionException.decryptionFailed(e);
        } catch (IllegalArgumentException e) {
            // Base64 decode failure
            throw CertVaultEncryptionException.invalidPayload(
                    "Encrypted payload contains invalid Base64: " + e.getMessage());
        }
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    /**
     * Validates and builds the {@link SecretKeySpec} from the raw Base64-encoded key string.
     *
     * <p>Requirements:
     * <ul>
     *   <li>Must not be null or blank (SecretValidator enforces this at startup)</li>
     *   <li>Must decode to <strong>exactly</strong> {@value REQUIRED_KEY_BYTES} bytes</li>
     * </ul>
     * Zero-padding short keys is explicitly forbidden — it dramatically reduces
     * effective entropy (e.g. a 16-byte key padded with zeros provides at most
     * 2^128 security, not 2^256, and is trivially detectable by an attacker who
     * knows the padding strategy).
     *
     * @throws CertVaultEncryptionException with reason {@code INVALID_KEY} if invalid
     */
    private static SecretKeySpec validateAndBuildKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw CertVaultEncryptionException.invalidKey(
                    "CERT_VAULT_ENCRYPTION_KEY is not set. " +
                    "Generate a valid key with: openssl rand -base64 32");
        }

        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw CertVaultEncryptionException.invalidKey(
                    "CERT_VAULT_ENCRYPTION_KEY is not valid Base64: " + e.getMessage());
        }

        if (keyBytes.length != REQUIRED_KEY_BYTES) {
            throw CertVaultEncryptionException.invalidKey(
                    "CERT_VAULT_ENCRYPTION_KEY must decode to exactly " + REQUIRED_KEY_BYTES +
                    " bytes (256 bits) for AES-256. Got " + keyBytes.length + " bytes. " +
                    "Generate a valid key with: openssl rand -base64 32");
        }

        return new SecretKeySpec(keyBytes, "AES");
    }

    // ─── Value types ──────────────────────────────────────────────────────────

    /**
     * Immutable record holding the three components of an AES-256-GCM encrypted payload.
     *
     * @param ciphertext Base64-encoded AES-256-GCM ciphertext (without auth tag)
     * @param iv         Base64-encoded 12-byte initialization vector (random per encryption)
     * @param tag        Base64-encoded 16-byte GCM authentication tag
     */
    public record EncryptedPayload(String ciphertext, String iv, String tag) {}
}



