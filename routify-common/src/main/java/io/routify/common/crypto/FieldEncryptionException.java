package io.routify.common.crypto;

import java.security.GeneralSecurityException;

/**
 * Typed exception for field-level encryption/decryption failures.
 *
 * <p>Thrown by {@link FieldEncryptionService} and surfaced through
 * {@link SensitiveStringConverter} when a {@code @Sensitive} field
 * cannot be encrypted or decrypted at the JPA persistence layer.
 *
 * <p>This is deliberately <strong>not</strong> part of the sealed
 * {@link io.routify.common.exception.RoutifyException} hierarchy because
 * it represents an infrastructure/crypto error rather than a domain error
 * and is never mapped to an HTTP response directly.
 */
public class FieldEncryptionException extends RuntimeException {

    public enum Reason {
        /** The AES key is missing, wrong length, or malformed Base64. */
        INVALID_KEY,
        /** The cipher operation failed during encryption. */
        ENCRYPTION_FAILED,
        /** The cipher operation failed during decryption (wrong key or tampered data). */
        DECRYPTION_FAILED,
        /** The stored encrypted payload is malformed or incomplete. */
        INVALID_PAYLOAD,
        /** The {@link FieldEncryptionService} has not been initialised yet. */
        NOT_INITIALISED
    }

    private final Reason reason;

    public FieldEncryptionException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public FieldEncryptionException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

    // ─── Convenience factories ────────────────────────────────────────────────

    public static FieldEncryptionException invalidKey(String detail) {
        return new FieldEncryptionException(Reason.INVALID_KEY, detail);
    }

    public static FieldEncryptionException encryptionFailed(GeneralSecurityException cause) {
        return new FieldEncryptionException(
                Reason.ENCRYPTION_FAILED,
                "AES-256-GCM field encryption failed: " + cause.getMessage(),
                cause);
    }

    public static FieldEncryptionException decryptionFailed(GeneralSecurityException cause) {
        return new FieldEncryptionException(
                Reason.DECRYPTION_FAILED,
                "AES-256-GCM field decryption failed: " + cause.getMessage(),
                cause);
    }

    public static FieldEncryptionException invalidPayload(String detail) {
        return new FieldEncryptionException(Reason.INVALID_PAYLOAD, detail);
    }

    public static FieldEncryptionException notInitialised() {
        return new FieldEncryptionException(
                Reason.NOT_INITIALISED,
                "FieldEncryptionService has not been initialised. " +
                "Ensure routify.field-encryption-key is set and " +
                "FieldEncryptionAutoConfiguration is active.");
    }
}

