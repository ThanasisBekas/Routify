package io.routify.gateway.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.routify.common.event.CommandEvent;
import io.routify.common.event.KafkaTopics;
import io.routify.gateway.filter.performance.ResponseCacheGatewayFilterFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer that listens for {@link CommandEvent.PurgeCacheRoute} commands
 * on the route commands topic and purges the response cache for the specified route.
 *
 * <p>Uses a unique consumer group ID per gateway instance ({@code routify-gateway-cache-purge})
 * to ensure every gateway instance receives and processes the purge command.
 * This is a broadcast pattern — all instances must clear their local Redis cache keys.
 *
 * <p>Note: Since all gateway instances share the same Redis, a single purge is sufficient,
 * but using a shared group ensures exactly-once processing across the cluster.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CachePurgeKafkaConsumer {

    private final ResponseCacheGatewayFilterFactory cacheFilterFactory;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = KafkaTopics.ROUTE_COMMANDS,
            groupId = "routify-gateway-cache-purge",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onRouteCommand(ConsumerRecord<String, String> record) {
        try {
            CommandEvent command = objectMapper.readValue(record.value(), CommandEvent.class);
            if (command instanceof CommandEvent.PurgeCacheRoute purge) {
                log.info("Received cache purge command for route={} tenant={} requestedBy={}",
                        purge.routeId(), purge.tenantId(), purge.requestedBy());
                cacheFilterFactory.purgeRoute(purge.routeId())
                        .subscribe(
                                count -> log.info("Cache purge complete for route={}: {} keys deleted",
                                        purge.routeId(), count),
                                error -> log.error("Cache purge failed for route={}: {}",
                                        purge.routeId(), error.getMessage(), error)
                        );
            }
            // Ignore other command types — they are handled by route-service
        } catch (Exception e) {
            log.debug("CachePurge: Ignoring non-parseable command record: {}", e.getMessage());
        }
    }
}

