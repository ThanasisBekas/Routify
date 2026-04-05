package io.routify.cert.exception;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import java.security.GeneralSecurityException;

/**
 * Typed exception for certificate vault encryption and decryption failures.
 *
 * <p>Replaces the generic {@code RuntimeException("Certificate encryption failed", cause)}
 * pattern so callers can distinguish between specific failure modes:
 *
 * <ul>
 *   <li>{@link Reason#INVALID_KEY} — the AES key is missing, wrong length, or malformed.</li>
 *   <li>{@link Reason#ENCRYPTION_FAILED} — the cipher operation failed during encryption.</li>
 *   <li>{@link Reason#DECRYPTION_FAILED} — the cipher operation failed during decryption,
 *       typically due to authentication tag mismatch (data tampering or wrong key).</li>
 *   <li>{@link Reason#INVALID_PAYLOAD} — the encrypted payload is malformed or incomplete.</li>
 * </ul>
 *
 * <p>The original {@link GeneralSecurityException} is always preserved as the
 * {@link #getCause()} so that the JVM security audit trail is not lost.
 */
public class CertVaultEncryptionException extends RuntimeException {

    public enum Reason {
        INVALID_KEY,
        ENCRYPTION_FAILED,
        DECRYPTION_FAILED,
        INVALID_PAYLOAD
    }

    private final Reason reason;

    public CertVaultEncryptionException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public CertVaultEncryptionException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

    // ─── Convenience factories ────────────────────────────────────────────────

    public static CertVaultEncryptionException invalidKey(String detail) {
        return new CertVaultEncryptionException(Reason.INVALID_KEY, detail);
    }

    public static CertVaultEncryptionException encryptionFailed(GeneralSecurityException cause) {
        return new CertVaultEncryptionException(
                Reason.ENCRYPTION_FAILED,
                "AES-256-GCM encryption failed: " + cause.getMessage(),
                cause);
    }

    public static CertVaultEncryptionException decryptionFailed(BadPaddingException cause) {
        // BadPaddingException during GCM decryption indicates authentication tag mismatch
        // — either data tampering or the wrong key was used.
        return new CertVaultEncryptionException(
                Reason.DECRYPTION_FAILED,
                "AES-256-GCM authentication tag verification failed — " +
                "possible data tampering or incorrect encryption key",
                cause);
    }

    public static CertVaultEncryptionException decryptionFailed(IllegalBlockSizeException cause) {
        return new CertVaultEncryptionException(
                Reason.DECRYPTION_FAILED,
                "AES-256-GCM decryption failed: invalid block size — " + cause.getMessage(),
                cause);
    }

    public static CertVaultEncryptionException decryptionFailed(GeneralSecurityException cause) {
        return new CertVaultEncryptionException(
                Reason.DECRYPTION_FAILED,
                "AES-256-GCM decryption failed: " + cause.getMessage(),
                cause);
    }

    public static CertVaultEncryptionException invalidPayload(String detail) {
        return new CertVaultEncryptionException(Reason.INVALID_PAYLOAD, detail);
    }
}

