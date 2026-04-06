package io.routify.admin.ws;

import io.routify.admin.gateway.service.GatewayActuatorClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * STOMP WebSocket controller — handles client lifecycle and periodic metric pushes.
 *
 * <p>On connect, the server immediately sends a welcome payload so the client
 * knows its subscription is live. Every 15 seconds, live gateway metrics
 * (circuit breaker states, request counts) are pushed to all subscribers of
 * {@code /topic/metrics} without any client-side polling.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class WebSocketController {

    private final SimpMessagingTemplate messaging;
    private final GatewayActuatorClient gatewayActuatorClient;

    private final AtomicInteger connectedClients = new AtomicInteger(0);

    // ─── Client lifecycle ──────────────────────────────────────────────────────

    @EventListener
    public void onClientConnected(SessionConnectedEvent event) {
        int count = connectedClients.incrementAndGet();
        StompHeaderAccessor sha = StompHeaderAccessor.wrap(event.getMessage());
        log.info("WebSocket client connected: session={} total={}", sha.getSessionId(), count);

        // Send welcome / ping to confirm subscription is live
        Object welcomePayload = Map.of(
                "type", "connected",
                "message", "Connected to Routify WebSocket",
                "connectedClients", count,
                "occurredAt", Instant.now().toString()
        );
        messaging.convertAndSend("/topic/events", welcomePayload);
    }

    @EventListener
    public void onClientDisconnected(SessionDisconnectEvent event) {
        int count = connectedClients.decrementAndGet();
        log.debug("WebSocket client disconnected: session={} remaining={}", event.getSessionId(), count);
    }

    // ─── Client → server ping ──────────────────────────────────────────────────

    /** Client can send a ping to /app/ping to verify the connection is alive */
    @MessageMapping("/ping")
    @SendTo("/topic/events")
    public Map<String, Object> handlePing() {
        return Map.of(
                "type", "pong",
                "occurredAt", Instant.now().toString(),
                "connectedClients", connectedClients.get()
        );
    }

    // ─── Periodic metrics broadcast ────────────────────────────────────────────

    /**
     * Every 15 seconds, push live gateway metrics to all subscribed dashboard clients.
     * This replaces client-side polling for circuit breaker states, request counts, etc.
     */
    @Scheduled(fixedDelay = 15_000)
    public void broadcastMetrics() {
        if (connectedClients.get() == 0) return; // skip if nobody is listening

        try {
            Map<String, Object> cbStates  = gatewayActuatorClient.getCircuitBreakerStates();
            Map<String, Object> health    = gatewayActuatorClient.getHealth();

            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("type",           "metrics");
            metrics.put("occurredAt",     Instant.now().toString());
            metrics.put("circuitBreakers", cbStates);
            metrics.put("health",         health);

            Object metricsPayload = metrics;
            messaging.convertAndSend("/topic/metrics", metricsPayload);
            log.debug("Pushed live metrics to {} WebSocket clients", connectedClients.get());

        } catch (Exception e) {
            log.warn("Failed to broadcast metrics: {}", e.getMessage());
        }
    }

    public int getConnectedClientCount() {
        return connectedClients.get();
    }
}

