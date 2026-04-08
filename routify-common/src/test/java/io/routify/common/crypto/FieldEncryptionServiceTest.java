package io.routify.common.crypto;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link FieldEncryptionService}.
 */
class FieldEncryptionServiceTest {

    /** Valid AES-256 key: 32 random bytes, Base64-encoded. */
    private static final String VALID_KEY = Base64.getEncoder().encodeToString(new byte[]{
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
            0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17,
            0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F
    });

    private static FieldEncryptionService service;

    @BeforeAll
    static void setUp() {
        service = new FieldEncryptionService(VALID_KEY);
    }

    // ─── Encrypt / Decrypt Round-Trip ────────────────────────────────────────

    @Nested
    @DisplayName("encrypt → decrypt round-trip")
    class RoundTrip {

        @Test
        @DisplayName("encrypts and decrypts short text correctly")
        void shortText() {
            String plaintext = "my-webhook-secret-123";
            String encrypted = service.encrypt(plaintext);
            assertNotNull(encrypted);
            assertTrue(encrypted.startsWith(FieldEncryptionService.ENC_PREFIX));
            assertNotEquals(plaintext, encrypted);

            String decrypted = service.decrypt(encrypted);
            assertEquals(plaintext, decrypted);
        }

        @Test
        @DisplayName("encrypts and decrypts empty string")
        void emptyString() {
            String encrypted = service.encrypt("");
            String decrypted = service.decrypt(encrypted);
            assertEquals("", decrypted);
        }

        @Test
        @DisplayName("encrypts and decrypts unicode text")
        void unicodeText() {
            String plaintext = "Ωmega-κey-日本語-🔑";
            String encrypted = service.encrypt(plaintext);
            String decrypted = service.decrypt(encrypted);
            assertEquals(plaintext, decrypted);
        }

        @Test
        @DisplayName("encrypts and decrypts long text")
        void longText() {
            String plaintext = "x".repeat(10_000);
            String encrypted = service.encrypt(plaintext);
            String decrypted = service.decrypt(encrypted);
            assertEquals(plaintext, decrypted);
        }

        @Test
        @DisplayName("each encryption produces a unique ciphertext (random IV)")
        void uniqueCiphertexts() {
            String plaintext = "same-input";
            String enc1 = service.encrypt(plaintext);
            String enc2 = service.encrypt(plaintext);
            assertNotEquals(enc1, enc2, "Random IV should produce unique ciphertexts");

            // Both should decrypt to the same plaintext
            assertEquals(plaintext, service.decrypt(enc1));
            assertEquals(plaintext, service.decrypt(enc2));
        }
    }

    // ─── Null Handling ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("null handling")
    class NullHandling {

        @Test
        @DisplayName("encrypt(null) returns null")
        void encryptNull() {
            assertNull(service.encrypt(null));
        }

        @Test
        @DisplayName("decrypt(null) returns null")
        void decryptNull() {
            assertNull(service.decrypt(null));
        }
    }

    // ─── Legacy Plaintext Passthrough ────────────────────────────────────────

    @Nested
    @DisplayName("legacy plaintext passthrough")
    class LegacyPlaintext {

        @Test
        @DisplayName("decrypt returns plaintext as-is when not encrypted")
        void plaintextPassthrough() {
            String plaintext = "unencrypted-legacy-value";
            String result = service.decrypt(plaintext);
            assertEquals(plaintext, result);
        }
    }

    // ─── isEncrypted ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("isEncrypted()")
    class IsEncrypted {

        @Test
        @DisplayName("returns true for encrypted values")
        void encryptedValue() {
            String encrypted = service.encrypt("test");
            assertTrue(service.isEncrypted(encrypted));
        }

        @Test
        @DisplayName("returns false for plaintext values")
        void plaintextValue() {
            assertFalse(service.isEncrypted("plaintext"));
        }

        @Test
        @DisplayName("returns false for null")
        void nullValue() {
            assertFalse(service.isEncrypted(null));
        }
    }

    // ─── Error Cases ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("error cases")
    class ErrorCases {

        @Test
        @DisplayName("rejects null key")
        void nullKey() {
            var ex = assertThrows(FieldEncryptionException.class,
                    () -> new FieldEncryptionService(null));
            assertEquals(FieldEncryptionException.Reason.INVALID_KEY, ex.getReason());
        }

        @Test
        @DisplayName("rejects blank key")
        void blankKey() {
            var ex = assertThrows(FieldEncryptionException.class,
                    () -> new FieldEncryptionService("   "));
            assertEquals(FieldEncryptionException.Reason.INVALID_KEY, ex.getReason());
        }

        @Test
        @DisplayName("rejects key with wrong length")
        void wrongLengthKey() {
            String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
            var ex = assertThrows(FieldEncryptionException.class,
                    () -> new FieldEncryptionService(shortKey));
            assertEquals(FieldEncryptionException.Reason.INVALID_KEY, ex.getReason());
        }

        @Test
        @DisplayName("rejects invalid Base64 key")
        void invalidBase64Key() {
            var ex = assertThrows(FieldEncryptionException.class,
                    () -> new FieldEncryptionService("not-valid-base64!!!"));
            assertEquals(FieldEncryptionException.Reason.INVALID_KEY, ex.getReason());
        }

        @Test
        @DisplayName("decrypt fails with tampered ciphertext")
        void tamperedCiphertext() {
            String encrypted = service.encrypt("secret-data");
            // Flip a character in the Base64 payload
            char[] chars = encrypted.toCharArray();
            int idx = FieldEncryptionService.ENC_PREFIX.length() + 5;
            chars[idx] = chars[idx] == 'A' ? 'B' : 'A';
            String tampered = new String(chars);

            assertThrows(FieldEncryptionException.class, () -> service.decrypt(tampered));
        }

        @Test
        @DisplayName("decrypt fails with wrong key")
        void wrongKey() {
            String encrypted = service.encrypt("secret-data");

            // Create a different service with a different key
            byte[] otherKeyBytes = new byte[32];
            otherKeyBytes[0] = (byte) 0xFF;
            String otherKey = Base64.getEncoder().encodeToString(otherKeyBytes);
            FieldEncryptionService otherService = new FieldEncryptionService(otherKey);

            assertThrows(FieldEncryptionException.class, () -> otherService.decrypt(encrypted));
        }

        @Test
        @DisplayName("decrypt fails with truncated payload")
        void truncatedPayload() {
            String encrypted = service.encrypt("data");
            // Truncate the Base64 payload to just the prefix + a few chars
            String truncated = FieldEncryptionService.ENC_PREFIX +
                    Base64.getEncoder().encodeToString(new byte[5]);

            assertThrows(FieldEncryptionException.class, () -> service.decrypt(truncated));
        }
    }

    // ─── Double Encryption Prevention ────────────────────────────────────────

    @Nested
    @DisplayName("double encryption prevention")
    class DoubleEncryption {

        @Test
        @DisplayName("SensitiveStringConverter does not double-encrypt")
        void noDoubleEncrypt() {
            // Set up the static holder so the converter can access the service
            FieldEncryptionService.setInstance(service);

            SensitiveStringConverter converter = new SensitiveStringConverter();
            String dbValue = converter.convertToDatabaseColumn("plaintext");
            assertTrue(service.isEncrypted(dbValue));

            // Converting the already-encrypted value again should be idempotent
            String dbValue2 = converter.convertToDatabaseColumn(dbValue);
            assertEquals(dbValue, dbValue2, "Should not double-encrypt");

            // Full round-trip
            String original = converter.convertToEntityAttribute(dbValue2);
            assertEquals("plaintext", original);
        }
    }
}

