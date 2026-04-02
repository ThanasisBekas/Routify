package gr.routify.gateway.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.common.event.RabbitTopology;
import gr.routify.gateway.routing.RouteSnapshotDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * RabbitMQ client for fetching route snapshots from routify-route-service.
 *
 * <p>Replaces the previous HTTP WebClient implementation. Uses the
 * RabbitMQ Direct Reply-To pattern for synchronous request/reply:
 *
 * <ol>
 *   <li>Sends an empty request to {@code routify.route-service} exchange
 *       with routing key {@code route.gateway.snapshot}</li>
 *   <li>route-service replies with the full JSON array of active routes</li>
 *   <li>Response is deserialized into a {@link Flux} of {@link RouteSnapshotDto}</li>
 * </ol>
 *
 * <p>The call is blocking at the AMQP level but is wrapped in a reactive
 * Flux for compatibility with the WebFlux-based gateway. The blocking
 * work runs on the caller's thread which is a bounded elastic scheduler thread
 * in reactive pipelines.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouteServiceClient {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper   objectMapper;

    /**
     * Fetches all active routes with their complete filter chain configurations
     * from routify-route-service via RabbitMQ request/reply.
     *
     * @return Flux of route snapshots
     */
    public Flux<RouteSnapshotDto> fetchGatewaySnapshot() {
        log.debug("Fetching gateway route snapshot from route-service via RabbitMQ...");
        return Flux.fromIterable(fetchGatewaySnapshotSync())
                .doOnComplete(() -> log.debug("Route snapshot fetch complete"))
                .doOnError(ex -> log.error("Failed to fetch route snapshot: {}", ex.getMessage()));
    }

    /**
     * Blocking fetch — used by the synchronized {@code DynamicRouteDefinitionLocator#refresh()}.
     */
    public List<RouteSnapshotDto> fetchGatewaySnapshotSync() {
        try {
            Object response = rabbitTemplate.convertSendAndReceive(
                    RabbitTopology.EXCHANGE_ROUTE_SERVICE,
                    RabbitTopology.RK_ROUTE_GATEWAY_SNAPSHOT,
                    "{}");

            if (response == null) {
                log.warn("route-service returned null for gateway snapshot (timeout or unavailable)");
                return List.of();
            }

            List<RouteSnapshotDto> snapshots = objectMapper.readValue(
                    response.toString(), new TypeReference<List<RouteSnapshotDto>>() {});
            log.info("Fetched {} active routes via RabbitMQ", snapshots.size());
            return snapshots;

        } catch (Exception e) {
            log.error("Failed to fetch gateway snapshot via RabbitMQ: {}", e.getMessage(), e);
            return List.of();
        }
    }
}
