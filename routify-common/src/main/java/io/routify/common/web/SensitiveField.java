package io.routify.common.web;

import java.lang.annotation.*;

/**
 * Marks a {@code String} field as sensitive (password, secret key, token, etc.).
 *
 * <p>Fields annotated with {@code @SensitiveField} are automatically replaced with
 * {@link Sensitive#MASK} by {@code Sensitive.maskFields(Object)} before an object
 * is serialised into an API response, ensuring that real secret values are never
 * leaked to clients.
 *
 * <p>On incoming writes (PUT / PATCH), callers should use {@link Sensitive#isMasked(String)}
 * to detect when a client echoed the placeholder back — in which case the stored value
 * must be preserved rather than overwritten.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * public class AuthProviderDto {
 *     private String clientId;
 *
 *     @SensitiveField
 *     private String clientSecret;
 *
 *     @SensitiveField
 *     private String password;
 * }
 *
 * // In a GET handler:
 * Sensitive.maskFields(dto);   // replaces annotated fields in-place
 * }</pre>
 *
 * @see Sensitive
 * @see Sensitive#isMasked(String)
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SensitiveField {
}

