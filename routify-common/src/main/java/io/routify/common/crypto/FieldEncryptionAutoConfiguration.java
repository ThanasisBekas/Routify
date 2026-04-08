package io.routify.common.crypto;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration that activates {@link FieldEncryptionService} for
 * {@code @Sensitive} field-level encryption in any Routify service that:
 *
 * <ol>
 *   <li>Has {@code jakarta.persistence-api} on the classpath (i.e. uses JPA).</li>
 *   <li>Sets the {@code routify.field-encryption-key} property (mapped from
 *       the {@code FIELD_ENCRYPTION_KEY} environment variable).</li>
 * </ol>
 *
 * <p>Services that do not meet both conditions (e.g. {@code admin-api},
 * {@code api-gateway}) will silently skip this configuration — no bean is
 * created and the {@code @Sensitive} annotation has no runtime effect.
 *
 * <p>On activation this configuration:
 * <ol>
 *   <li>Creates the {@link FieldEncryptionService} bean (validates the key
 *       and performs an encrypt/decrypt round-trip at startup).</li>
 *   <li>Injects the bean into the static holder used by
 *       {@link SensitiveStringConverter} so that the JPA converter (which is
 *       not Spring-managed) can access the encryption service.</li>
 * </ol>
 *
 * <p>Registered via
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 *
 * @see Sensitive
 * @see FieldEncryptionService
 * @see SensitiveStringConverter
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(name = "jakarta.persistence.AttributeConverter")
@ConditionalOnProperty(name = "routify.field-encryption-key")
public class FieldEncryptionAutoConfiguration {

    @Bean
    public FieldEncryptionService fieldEncryptionService(
            @org.springframework.beans.factory.annotation.Value("${routify.field-encryption-key}") String base64Key) {
        FieldEncryptionService service = new FieldEncryptionService(base64Key);
        FieldEncryptionService.setInstance(service);
        log.info("FieldEncryptionAutoConfiguration: @Sensitive field encryption activated");
        return service;
    }
}

