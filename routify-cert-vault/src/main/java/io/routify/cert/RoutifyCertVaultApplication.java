package io.routify.cert;

import io.routify.common.config.SecretValidator;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Routify Certificate Vault — Secure inbound certificate storage and management.
 *
 * <p>Allows dashboard users to upload inbound TLS certificates (PEM/PKCS12),
 * stores them encrypted in PostgreSQL, and exposes logical cert IDs that
 * gateway TLS configuration can reference for inbound mTLS termination.
 *
 * <p>Communication pattern follows the standard Routify architecture:
 * <ul>
 *   <li>Write commands (upload/revoke/delete) arrive via Kafka from routify-admin-api</li>
 *   <li>Read queries (list/get/snapshot) are served via RabbitMQ request/reply</li>
 *   <li>Domain events are published to Kafka via the Transactional Outbox pattern</li>
 * </ul>
 */
@SpringBootApplication
@EnableScheduling
public class RoutifyCertVaultApplication {
    public static void main(String[] args) {
        new SpringApplicationBuilder(RoutifyCertVaultApplication.class)
                .listeners(new SecretValidator())
                .run(args);
    }
}

