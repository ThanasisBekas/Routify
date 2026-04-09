package io.routify.admin.config;

import io.routify.common.crypto.FieldEncryptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Manual wiring of {@link FieldEncryptionService} for the admin-api.
 *
 * <p>The shared {@code FieldEncryptionAutoConfiguration} in routify-common
 * requires JPA on the classpath, which the admin-api does not have.
 * This config creates the same bean so that {@link io.routify.admin.gateway.service.GatewayConfigService}
 * can AES-encrypt auth provider passwords and other secrets at rest.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "routify.field-encryption-key")
public class FieldEncryptionConfig {

    @Bean
    public FieldEncryptionService fieldEncryptionService(
            @Value("${routify.field-encryption-key}") String base64Key) {
        FieldEncryptionService service = new FieldEncryptionService(base64Key);
        log.info("FieldEncryptionConfig: AES-256-GCM encryption activated for gateway config secrets");
        return service;
    }
}

