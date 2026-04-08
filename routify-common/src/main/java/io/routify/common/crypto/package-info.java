/**
 * Transparent field-level encryption for JPA entities using AES-256-GCM.
 *
 * <p>Annotate a {@code String} entity field with {@link io.routify.common.crypto.Sensitive @Sensitive}
 * to have it automatically encrypted when persisted to the database and decrypted when read.
 *
 * <h2>Components</h2>
 * <ul>
 *   <li>{@link io.routify.common.crypto.Sensitive @Sensitive} — meta-annotation combining
 *       {@code @Convert(converter = SensitiveStringConverter.class)} for one-liner usage.</li>
 *   <li>{@link io.routify.common.crypto.FieldEncryptionService FieldEncryptionService} —
 *       AES-256-GCM encrypt/decrypt engine using a compact single-column storage format
 *       ({@code {enc}} prefix + Base64-encoded IV || ciphertext || GCM-tag).</li>
 *   <li>{@link io.routify.common.crypto.SensitiveStringConverter SensitiveStringConverter} —
 *       JPA {@code AttributeConverter} that delegates to {@code FieldEncryptionService}.</li>
 *   <li>{@link io.routify.common.crypto.FieldEncryptionAutoConfiguration FieldEncryptionAutoConfiguration} —
 *       Spring Boot auto-configuration that activates when
 *       {@code routify.field-encryption-key} is set and JPA is on the classpath.</li>
 *   <li>{@link io.routify.common.crypto.FieldEncryptionException FieldEncryptionException} —
 *       typed exception with distinct {@code Reason} values for key, encrypt, decrypt, and payload errors.</li>
 * </ul>
 *
 * <h2>Backwards compatibility</h2>
 * <p>The converter detects unencrypted (legacy) values on read and returns them as-is,
 * enabling a gradual migration. New writes are always encrypted.
 *
 * @see io.routify.common.crypto.Sensitive
 */
package io.routify.common.crypto;

