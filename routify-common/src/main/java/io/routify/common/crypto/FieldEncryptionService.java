package io.routify.common.crypto;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption service for {@code @Sensitive} entity fields.
 *
 * <p>Provides transparent encrypt/decrypt operations used by
 * {@link SensitiveStringConverter} at the JPA {@code AttributeConverter} layer.
 * Each encryption operation generates a fresh 12-byte IV; the output is a
 * single compact Base64 string containing {@code IV || ciphertext || GCM-tag},
 * suitable for storage in a single {@code TEXT} column.
 *
 * <h2>Key requirements</h2>
 * <p>The encryption key is supplied via the {@code FIELD_ENCRYPTION_KEY}
 * environment variable (mapped to {@code routify.field-encryption-key}).
 * It must be a Base64-encoded string that decodes to <strong>exactly 32 bytes</strong>
 * (AES-256).
 *
 * <p>Generate a valid key with: {@code openssl rand -base64 32}
 *
 * <h2>Static holder pattern</h2>
 * <p>JPA {@code AttributeConverter} instances are <em>not</em> Spring-managed beans.
 * To bridge the gap, this service publishes itself into a package-private static
 * holder ({@link #setInstance(FieldEncryptionService)}) during initialisation so
 * that {@link SensitiveStringConverter} can access it without dependency injection.
 * The {@link FieldEncryptionAutoConfiguration} class orchestrates this wiring.
 *
 * @see Sensitive
 * @see SensitiveStringConverter
 * @see FieldEncryptionAutoConfiguration
 */
@Slf4j
public class FieldEncryptionService {

    private static final String ALGORITHM        = "AES/GCM/NoPadding";
    private static final int    GCM_TAG_BITS     = 128;
    private static final int    GCM_TAG_BYTES    = GCM_TAG_BITS / 8; // 16
    private static final int    IV_BYTES         = 12;
    private static final int    REQUIRED_KEY_BYTES = 32;

    /** Compact encrypted payload prefix to detect already-encrypted values. */
    static final String ENC_PREFIX = "{enc}";

    private final SecretKeySpec secretKey;
    private final SecureRandom  random = new SecureRandom();

    // ─── Static holder for AttributeConverter access ─────────────────────────

    private static volatile FieldEncryptionService INSTANCE;

    /**
     * Returns the singleton instance, or throws if not yet initialised.
     * Called by {@link SensitiveStringConverter}.
     */
    static FieldEncryptionService getInstance() {
        FieldEncryptionService svc = INSTANCE;
        if (svc == null) {
            throw FieldEncryptionException.notInitialised();
        }
        return svc;
    }

    /**
     * Called by {@link FieldEncryptionAutoConfiguration} after the bean is created.
     */
    static void setInstance(FieldEncryptionService service) {
        INSTANCE = service;
    }

    // ─── Construction & Validation ───────────────────────────────────────────

    public FieldEncryptionService(String base64Key) {
        this.secretKey = validateAndBuildKey(base64Key);
    }

    /**
     * Performs an in-memory encrypt/decrypt round-trip to verify the key works
     * correctly.  Fails fast with a clear error before the application context
     * finishes loading.
     */
    @PostConstruct
    void validateKeyRoundTrip() {
        try {
            String probe = "routify-field-encryption-probe";
            String encrypted = encrypt(probe);
            String recovered = decrypt(encrypted);
            if (!probe.equals(recovered)) {
                throw FieldEncryptionException.invalidKey(
                        "AES-256-GCM round-trip validation failed — " +
                        "recovered plaintext does not match probe. " +
                        "Check FIELD_ENCRYPTION_KEY.");
            }
            log.info("FieldEncryptionService: AES-256-GCM key validated successfully");
        } catch (FieldEncryptionException e) {
            throw e;
        } catch (Exception e) {
            throw FieldEncryptionException.invalidKey(
                    "AES-256-GCM key round-trip validation failed: " + e.getMessage());
        }
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Encrypts the given plaintext and returns a compact single-string payload.
     *
     * <p>Format: {@code {enc}<base64(IV || ciphertext || GCM-tag)>}
     *
     * @param plaintext UTF-8 plaintext to encrypt — must not be null
     * @return encrypted string with {@value ENC_PREFIX} prefix
     * @throws FieldEncryptionException on crypto error
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));

            byte[] cipherWithTag = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Pack IV + ciphertext+tag into a single byte array
            ByteBuffer buffer = ByteBuffer.allocate(IV_BYTES + cipherWithTag.length);
            buffer.put(iv);
            buffer.put(cipherWithTag);

            return ENC_PREFIX + Base64.getEncoder().encodeToString(buffer.array());

        } catch (GeneralSecurityException e) {
            throw FieldEncryptionException.encryptionFailed(e);
        }
    }

    /**
     * Decrypts a previously encrypted payload back to its original plaintext.
     *
     * @param encryptedValue the string returned by {@link #encrypt(String)},
     *                       including the {@value ENC_PREFIX} prefix
     * @return original plaintext as a UTF-8 string
     * @throws FieldEncryptionException on crypto or payload error
     */
    public String decrypt(String encryptedValue) {
        if (encryptedValue == null) {
            return null;
        }
        if (!encryptedValue.startsWith(ENC_PREFIX)) {
            // Value is not encrypted (e.g. legacy plaintext data) — return as-is
            return encryptedValue;
        }

        String base64Payload = encryptedValue.substring(ENC_PREFIX.length());

        try {
            byte[] raw = Base64.getDecoder().decode(base64Payload);

            if (raw.length < IV_BYTES + GCM_TAG_BYTES) {
                throw FieldEncryptionException.invalidPayload(
                        "Encrypted payload too short: expected at least " +
                        (IV_BYTES + GCM_TAG_BYTES) + " bytes, got " + raw.length);
            }

            ByteBuffer buffer = ByteBuffer.wrap(raw);
            byte[] iv = new byte[IV_BYTES];
            buffer.get(iv);

            byte[] cipherWithTag = new byte[buffer.remaining()];
            buffer.get(cipherWithTag);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plainBytes = cipher.doFinal(cipherWithTag);

            return new String(plainBytes, StandardCharsets.UTF_8);

        } catch (FieldEncryptionException e) {
            throw e;
        } catch (BadPaddingException e) {
            throw new FieldEncryptionException(
                    FieldEncryptionException.Reason.DECRYPTION_FAILED,
                    "AES-256-GCM authentication tag verification failed — " +
                    "possible data tampering or incorrect encryption key",
                    e);
        } catch (IllegalBlockSizeException e) {
            throw new FieldEncryptionException(
                    FieldEncryptionException.Reason.DECRYPTION_FAILED,
                    "AES-256-GCM decryption failed: invalid block size — " + e.getMessage(),
                    e);
        } catch (GeneralSecurityException e) {
            throw FieldEncryptionException.decryptionFailed(e);
        } catch (IllegalArgumentException e) {
            throw FieldEncryptionException.invalidPayload(
                    "Encrypted payload contains invalid Base64: " + e.getMessage());
        }
    }

    /**
     * Returns {@code true} if the value is already encrypted (carries the
     * {@value ENC_PREFIX} prefix).
     */
    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(ENC_PREFIX);
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private static SecretKeySpec validateAndBuildKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw FieldEncryptionException.invalidKey(
                    "FIELD_ENCRYPTION_KEY is not set. " +
                    "Generate a valid key with: openssl rand -base64 32");
        }

        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw FieldEncryptionException.invalidKey(
                    "FIELD_ENCRYPTION_KEY is not valid Base64: " + e.getMessage());
        }

        if (keyBytes.length != REQUIRED_KEY_BYTES) {
            throw FieldEncryptionException.invalidKey(
                    "FIELD_ENCRYPTION_KEY must decode to exactly " + REQUIRED_KEY_BYTES +
                    " bytes (256 bits) for AES-256. Got " + keyBytes.length + " bytes. " +
                    "Generate a valid key with: openssl rand -base64 32");
        }

        return new SecretKeySpec(keyBytes, "AES");
    }
}


