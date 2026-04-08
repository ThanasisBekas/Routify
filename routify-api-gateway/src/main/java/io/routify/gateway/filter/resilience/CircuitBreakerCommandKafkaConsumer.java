package io.routify.gateway.filter.resilience;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.routify.common.event.CommandEvent;
import io.routify.common.event.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer that listens for {@link CommandEvent.ForceCircuitBreaker} commands
 * on the route commands topic and forces circuit breaker state transitions.
 *
 * <p>Uses a broadcast consumer group ({@code routify-gateway-circuit-breaker}) so that
 * every gateway instance receives and processes the command — each instance has its own
 * in-memory circuit breaker registry.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CircuitBreakerCommandKafkaConsumer {

    private final CircuitBreakerV2GatewayFilterFactory cbFilterFactory;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = KafkaTopics.ROUTE_COMMANDS,
            groupId = "routify-gateway-circuit-breaker",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onRouteCommand(ConsumerRecord<String, String> record) {
        try {
            CommandEvent command = objectMapper.readValue(record.value(), CommandEvent.class);
            if (command instanceof CommandEvent.ForceCircuitBreaker force) {
                handleForceCircuitBreaker(force);
            }
            // Ignore other command types
        } catch (Exception e) {
            log.debug("CircuitBreakerCommand: Ignoring non-parseable command record: {}", e.getMessage());
        }
    }

    private void handleForceCircuitBreaker(CommandEvent.ForceCircuitBreaker command) {
        String routeId = command.routeId().toString();
        CircuitBreaker cb = cbFilterFactory.getCircuitBreaker(routeId);

        if (cb == null) {
            log.warn("CircuitBreakerCommand: No circuit breaker found for route={}, action={}",
                    routeId, command.action());
            return;
        }

        log.info("CircuitBreakerCommand: route={} action={} requestedBy={}",
                routeId, command.action(), command.requestedBy());

        switch (command.action()) {
            case "FORCE_OPEN" -> {
                cb.transitionToForcedOpenState();
                log.info("CircuitBreakerCommand: forced OPEN for route={}", routeId);
            }
            case "FORCE_CLOSED" -> {
                cb.transitionToClosedState();
                log.info("CircuitBreakerCommand: forced CLOSED for route={}", routeId);
            }
            case "RESET" -> {
                cb.reset();
                log.info("CircuitBreakerCommand: RESET for route={}", routeId);
            }
            default -> log.warn("CircuitBreakerCommand: unknown action '{}' for route={}",
                    command.action(), routeId);
        }
    }
}

