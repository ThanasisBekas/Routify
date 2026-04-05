package io.routify.route;

import io.routify.common.config.SecretValidator;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Routify Route Service — manages Route and Filter definitions.
 *
 * <p>Key responsibilities:
 * <ul>
 *   <li>CRUD for Routes (DRAFT → ACTIVE → DISABLED lifecycle)</li>
 *   <li>CRUD for FilterDefinitions (auth, rate limit, transform, etc.)</li>
 *   <li>Attaching/detaching filters to routes</li>
 *   <li>Publishing domain events via Transactional Outbox → Kafka</li>
 *   <li>Exposing /api/v1/routes/gateway/snapshot for gateway hot-reload</li>
 * </ul>
 *
 * <p>Zero-downtime route activation:
 * Activating a route publishes a {@code GatewayReloadRequested} event to Kafka.
 * The {@code routify-api-gateway} service subscribes to this topic and calls
 * its {@code DynamicRouteRefreshListener} which rebuilds the
 * {@code RouteDefinitionLocator} without restarting the gateway.
 */
@SpringBootApplication
@EnableScheduling
@EnableCaching
public class RoutifyRouteServiceApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(RoutifyRouteServiceApplication.class)
                .listeners(new SecretValidator())
                .run(args);
    }
}

