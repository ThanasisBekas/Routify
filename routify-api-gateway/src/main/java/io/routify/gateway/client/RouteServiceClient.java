package io.routify.gateway.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.routify.common.client.AmqpServiceClientSupport;
import io.routify.common.event.QueryRequest;
import io.routify.common.event.QueryResponse;
import io.routify.common.event.RabbitTopology;
import io.routify.gateway.routing.RouteSnapshotDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * RabbitMQ client for fetching route snapshots from routify-route-service.
 *
 * <p>Uses the RabbitMQ Direct Reply-To pattern for synchronous request/reply via
 * {@link AmqpServiceClientSupport}, which serialises the strongly-typed
 * {@link QueryRequest.GatewaySnapshot} with the Jackson {@code "type"} discriminator
 * that route-service's {@code Jackson2JsonMessageConverter} requires for deserialisation.
 *
 * <ol>
 *   <li>Sends a {@link QueryRequest.GatewaySnapshot} to the {@code routify.route-service}
 *       exchange with routing key {@code route.gateway.snapshot}</li>
 *   <li>route-service replies with a {@link QueryResponse.GatewaySnapshotList}</li>
 *   <li>Response is mapped into a {@link List} / {@link Flux} of {@link RouteSnapshotDto}</li>
 * </ol>
 *
 * <p>The call is blocking at the AMQP level but is wrapped in a reactive
 * Flux for compatibility with the WebFlux-based gateway. The blocking
 * work runs on the caller's thread which is a bounded elastic scheduler thread
 * in reactive pipelines.
 */
@Slf4j
@Component
public class RouteServiceClient extends AmqpServiceClientSupport {

    public RouteServiceClient(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        super(rabbitTemplate, objectMapper, RabbitTopology.EXCHANGE_ROUTE_SERVICE, "api-gateway");
    }

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
            QueryResponse.GatewaySnapshotList response = rpc(
                    RabbitTopology.RK_ROUTE_GATEWAY_SNAPSHOT,
                    new QueryRequest.GatewaySnapshot(),
                    QueryResponse.GatewaySnapshotList.class);

            List<RouteSnapshotDto> snapshots = response.routes().stream()
                    .map(r -> new RouteSnapshotDto(
                            r.routeId(), r.tenantId(), r.name(), r.pathPattern(),
                            r.methods(), r.upstreamUri(), r.stripPrefix(), r.version(),
                            r.filters() == null ? List.of() : r.filters().stream()
                                    .map(f -> new RouteSnapshotDto.FilterSnapshotDto(
                                            f.filterId(), f.filterType(), f.order(), f.phase(),
                                            f.config(), f.gatewayConfigRef()))
                                    .toList(),
                            r.extraConfig()))
                    .toList();

            log.info("Fetched {} active routes via RabbitMQ", snapshots.size());
            return snapshots;

        } catch (Exception e) {
            log.error("Failed to fetch gateway snapshot via RabbitMQ: {}", e.getMessage(), e);
            return List.of();
        }
    }
}
