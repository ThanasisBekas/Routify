package io.routify.identity;

import io.routify.common.config.SecretValidator;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Routify Identity Service — Authentication and user management.
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
@EnableCaching
public class RoutifyIdentityServiceApplication {
    public static void main(String[] args) {
        new SpringApplicationBuilder(RoutifyIdentityServiceApplication.class)
                .listeners(new SecretValidator())
                .run(args);
    }
}

