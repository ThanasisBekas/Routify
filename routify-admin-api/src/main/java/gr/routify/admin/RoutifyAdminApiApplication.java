package gr.routify.admin;

import gr.routify.common.config.SecretValidator;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Routify Admin API — Backend for Frontend (BFF) for the dashboard.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Aggregate data from multiple microservices for the dashboard</li>
 *   <li>Real-time events via WebSocket/STOMP (primary) and SSE (legacy fallback)</li>
 *   <li>Platform statistics, monitoring, gateway configuration management</li>
 * </ul>
 */
@SpringBootApplication
@EnableScheduling
public class RoutifyAdminApiApplication {
    public static void main(String[] args) {
        new SpringApplicationBuilder(RoutifyAdminApiApplication.class)
                .listeners(new SecretValidator())
                .run(args);
    }
}

