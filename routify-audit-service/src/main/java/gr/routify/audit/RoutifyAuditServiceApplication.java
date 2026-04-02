package gr.routify.audit;

import gr.routify.common.config.SecretValidator;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Routify Audit Service — Immutable event store and request analytics.
 */
@SpringBootApplication
@EnableScheduling
public class RoutifyAuditServiceApplication {
    public static void main(String[] args) {
        new SpringApplicationBuilder(RoutifyAuditServiceApplication.class)
                .listeners(new SecretValidator())
                .run(args);
    }
}

