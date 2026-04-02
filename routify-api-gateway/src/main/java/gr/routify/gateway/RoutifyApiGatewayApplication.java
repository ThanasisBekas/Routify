package gr.routify.gateway;

import gr.routify.common.config.SecretValidator;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.oauth2.resource.reactive.ReactiveOAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.security.Security;

/**
 * Routify API Gateway — Spring Cloud Gateway with zero-downtime dynamic routing.
 *
 * <h2>Architecture Overview:</h2>
 * <ul>
 *   <li><b>DynamicRouteDefinitionLocator</b> — provides routes to SCG from Redis/route-service</li>
 *   <li><b>DynamicRouteRefreshListener</b> — subscribes to Kafka for route change events</li>
 *   <li><b>Filter Factory chain</b> — JWT, API Key, Rate Limit, Transform, etc.</li>
 *   <li><b>Zero downtime</b> — route changes propagate via Kafka in &lt;500ms</li>
 * </ul>
 *
 * <h2>Zero-Downtime Route Activation Flow:</h2>
 * <ol>
 *   <li>User activates route in dashboard →</li>
 *   <li>routify-route-service persists + publishes GatewayReloadRequested to Kafka →</li>
 *   <li>DynamicRouteRefreshListener receives event →</li>
 *   <li>DynamicRouteDefinitionLocator fetches snapshot from route-service →</li>
 *   <li>Spring Cloud Gateway re-routes subsequent requests instantly →</li>
 *   <li>In-flight requests complete normally — zero disruption</li>
 * </ol>
 */
@SpringBootApplication(exclude = ReactiveOAuth2ResourceServerAutoConfiguration.class)
@EnableScheduling
@ConfigurationPropertiesScan
public class RoutifyApiGatewayApplication {

    public static void main(String[] args) {
        Security.addProvider(new BouncyCastleProvider());
        new SpringApplicationBuilder(RoutifyApiGatewayApplication.class)
                .listeners(new SecretValidator())
                .run(args);
    }
}

