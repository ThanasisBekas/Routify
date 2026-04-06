package io.routify.common.web;

import java.lang.reflect.Field;
import java.util.Collection;

/**
 * Utilities for handling sensitive field values (passwords, secrets, API keys, etc.)
 * in API responses and persistence flows.
 *
 * <h2>Masking pattern</h2>
 * <p>When a secret value is returned to the client its real content is replaced with
 * {@link #MASK} — a fixed sentinel string. When the client submits the object back
 * (e.g. on a PUT / PATCH), callers check {@link #isMasked(String)} and, if the
 * sentinel is detected, preserve the currently stored secret rather than overwriting
 * it with the placeholder.
 *
 * <h2>Annotation-driven masking</h2>
 * <p>String fields annotated with {@link SensitiveField} are automatically redacted
 * by {@link #maskFields(Object)} without any manual field-by-field code. Nested
 * objects and collections of objects are traversed recursively.
 *
 * <pre>{@code
 * // 1. Annotate sensitive fields in the DTO:
 * public class AuthProviderDto {
 *     private String clientId;
 *
 *     @SensitiveField
 *     private String clientSecret;
 * }
 *
 * // 2. Mask before returning to the client:
 * Sensitive.maskFields(dto);          // mutates in-place
 *
 * // 3. On incoming write — guard against echo-back:
 * if (!Sensitive.isMasked(incoming.getClientSecret())) {
 *     stored.setClientSecret(incoming.getClientSecret());
 * }
 * }</pre>
 */
public final class Sensitive {

    /**
     * Sentinel value returned to clients in place of a real secret.
     * Any incoming value equal to this string must never be persisted
     * back over the real stored secret.
     */
    public static final String MASK = "[REDACTED]";

    private Sensitive() {}

    // ─── Scalar helpers ───────────────────────────────────────────────────────

    /**
     * Returns {@link #MASK} when {@code value} is non-null and non-blank;
     * returns {@code null} otherwise (preserving the absence of a secret).
     *
     * @param value the raw secret value, may be {@code null}
     * @return {@link #MASK} or {@code null}
     */
    public static String mask(String value) {
        return (value != null && !value.isBlank()) ? MASK : null;
    }

    /**
     * Returns {@code true} when {@code value} equals {@link #MASK} — indicating
     * that the client echoed the placeholder back and the caller should retain
     * the currently stored secret.
     *
     * @param value the value submitted by the client, may be {@code null}
     * @return {@code true} if the value is the mask sentinel
     */
    public static boolean isMasked(String value) {
        return MASK.equals(value);
    }

    // ─── Reflection-based masking ─────────────────────────────────────────────

    /**
     * Traverses all declared {@code String} fields of {@code obj} (including
     * inherited fields) and replaces the value of every field annotated with
     * {@link SensitiveField} with {@link #MASK} (or {@code null} if the field
     * was already {@code null} / blank).
     *
     * <p>Recursion rules:
     * <ul>
     *   <li>Non-primitive, non-JDK object fields are recursed into.</li>
     *   <li>{@link Collection} fields are iterated and each element is recursed.</li>
     *   <li>The method is a no-op for {@code null} inputs.</li>
     * </ul>
     *
     * <p>The object is mutated in-place; no copy is made.
     *
     * @param obj the object whose sensitive fields should be masked; may be {@code null}
     */
    public static void maskFields(Object obj) {
        if (obj == null) return;
        maskFieldsInternal(obj, obj.getClass());
    }

    private static void maskFieldsInternal(Object obj, Class<?> clazz) {
        if (clazz == null || clazz == Object.class) return;

        for (Field field : clazz.getDeclaredFields()) {
            field.setAccessible(true);
            try {
                if (field.getType() == String.class
                        && field.isAnnotationPresent(SensitiveField.class)) {
                    String current = (String) field.get(obj);
                    field.set(obj, mask(current));
                } else if (!field.getType().isPrimitive()
                        && !field.getType().getName().startsWith("java.")
                        && !field.getType().isEnum()) {
                    // Recurse into nested DTOs
                    Object nested = field.get(obj);
                    if (nested != null) maskFields(nested);
                } else if (Collection.class.isAssignableFrom(field.getType())) {
                    Object value = field.get(obj);
                    if (value instanceof Collection<?> col) {
                        col.forEach(Sensitive::maskFields);
                    }
                }
            } catch (IllegalAccessException ignored) {
                // Best-effort: skip inaccessible fields
            }
        }

        // Walk up the class hierarchy
        maskFieldsInternal(obj, clazz.getSuperclass());
    }
}

