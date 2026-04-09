package io.routify.common.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA {@link AttributeConverter} that transparently encrypts {@code String}
 * entity fields on persist and decrypts them on read using AES-256-GCM.
 *
 * <p>This converter is activated on any entity field annotated with
 * {@link Sensitive @Sensitive}, which bundles the
 * {@code @Convert(converter = SensitiveStringConverter.class)} meta-annotation.
 *
 * <h2>How it works</h2>
 * <ol>
 *   <li><b>Write path</b> — {@link #convertToDatabaseColumn(String)} encrypts
 *       plaintext into a compact {@code {enc}base64(IV||ciphertext||tag)} string
 *       via {@link FieldEncryptionService#encrypt(String)}.</li>
 *   <li><b>Read path</b> — {@link #convertToEntityAttribute(String)} decrypts
 *       the stored value back to plaintext. If the value does not carry the
 *       {@code {enc}} prefix (legacy plaintext), it is returned as-is for
 *       backwards compatibility during migration.</li>
 * </ol>
 *
 * <h2>Spring wiring</h2>
 * <p>JPA instantiates converters outside the Spring context so dependency
 * injection is unavailable.  {@link FieldEncryptionAutoConfiguration} injects
 * the {@link FieldEncryptionService} bean into a static holder that this
 * converter reads from.  The auto-configuration is conditional on the
 * {@code routify.field-encryption-key} property being set — services that
 * do not provide the key will not activate encryption.
 *
 * @see Sensitive
 * @see FieldEncryptionService
 * @see FieldEncryptionAutoConfiguration
 */
@Converter
public class SensitiveStringConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) {
            return null;
        }
        // Avoid double-encryption: if already encrypted, return as-is
        FieldEncryptionService service = FieldEncryptionService.getInstance();
        if (service.isEncrypted(attribute)) {
            return attribute;
        }
        return service.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        return FieldEncryptionService.getInstance().decrypt(dbData);
    }
}

