package io.routify.common.crypto;

import jakarta.persistence.Convert;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code String} entity field for transparent AES-256-GCM
 * encryption at rest.
 *
 * <p>When a field is annotated with {@code @Sensitive}, JPA automatically
 * encrypts the value before writing it to the database column and decrypts
 * it when reading it back into the entity.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @Entity
 * public class WebhookSubscription {
 *
 *     @Sensitive
 *     @Column(nullable = false)
 *     private String secret;
 * }
 * }</pre>
 *
 * <h2>Requirements</h2>
 * <ul>
 *   <li>The annotated field must be of type {@code String}.</li>
 *   <li>The service must set the {@code routify.field-encryption-key} property
 *       (mapped from the {@code FIELD_ENCRYPTION_KEY} environment variable).</li>
 *   <li>The column must be wide enough to hold the encrypted payload
 *       (approximately 33% larger than plaintext due to Base64 + IV + tag
 *       overhead, plus the 5-char {@code {enc}} prefix). Using
 *       {@code columnDefinition = "TEXT"} is recommended.</li>
 * </ul>
 *
 * <h2>Backwards compatibility</h2>
 * <p>The underlying {@link SensitiveStringConverter} detects legacy plaintext
 * values (those without the {@code {enc}} prefix) and returns them as-is on
 * read.  This allows a gradual migration: existing rows are decrypted
 * transparently once they are re-written (updated) by the application, or
 * a one-time Flyway migration can batch-encrypt all existing data.
 *
 * @see SensitiveStringConverter
 * @see FieldEncryptionService
 * @see FieldEncryptionAutoConfiguration
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
@Convert(converter = SensitiveStringConverter.class)
public @interface Sensitive {
}

