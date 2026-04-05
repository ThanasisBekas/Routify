package io.routify.admin.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket / STOMP configuration for the Routify admin dashboard.
 *
 * <p>Clients connect to {@code /ws} (with SockJS fallback) and subscribe to:
 * <ul>
 *   <li>{@code /topic/events} — all domain events (route, filter, gateway, config,
 *       certificate, cert-group, user, tenant, audit, replay)</li>
 *   <li>{@code /topic/metrics} — live gateway metrics (circuit breakers, request rates)</li>
 *   <li>{@code /topic/audit}  — live audit events as they are persisted</li>
 * </ul>
 *
 * <p>Compared to SSE, WebSocket provides:
 * <ul>
 *   <li>Bidirectional communication (client can send ACKs, subscriptions)</li>
 *   <li>Better proxy/load-balancer compatibility (no chunked-transfer-encoding issues)</li>
 *   <li>Multiplexed topics on a single TCP connection (no per-topic HTTP keep-alive)</li>
 *   <li>Proper connection lifecycle events (CONNECT / DISCONNECT / HEARTBEAT)</li>
 * </ul>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // In-memory simple broker for these topic prefixes
        config.enableSimpleBroker("/topic");
        // Application destination prefix for @MessageMapping methods
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Raw WebSocket endpoint — used by the dashboard's native WebSocket client
        // (useWebSocket.ts connects to /ws/websocket with plain STOMP over WS).
        registry.addEndpoint("/ws/websocket")
                .setAllowedOriginPatterns("*");

        // SockJS fallback endpoint — for environments where native WS is blocked.
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }
}

