package gr.routify.admin.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.common.event.KafkaTopics;
import gr.routify.common.event.RabbitTopology;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Admin-API messaging client for user and tenant operations in routify-identity-service.
 *
 * <p><b>Queries</b> use RabbitMQ request/reply to identity-service.
 * <b>Commands</b> (create/update/delete user, create/suspend/reactivate tenant)
 * are published as Kafka command events consumed by identity-service.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdentityMessagingClient {

    private final RabbitTemplate               rabbitTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper                 objectMapper;

    // ─── Auth (RabbitMQ) ──────────────────────────────────────────────────────

    @CircuitBreaker(name = "identity-service", fallbackMethod = "loginFallback")
    public Map<String, Object> login(String username, String password, String tenantSlug) {
        try {
            var req = Map.of("username", username, "password", password, "tenantSlug", tenantSlug);
            return rpcIdentityService(RabbitTopology.RK_AUTH_LOGIN, req);
        } catch (Exception e) {
            log.error("login RPC failed: {}", e.getMessage(), e);
            return Map.of("error", e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private Map<String, Object> loginFallback(String username, String password,
                                               String tenantSlug, Throwable t) {
        log.warn("login circuit open or timed out: {}", t.getMessage());
        return Map.of("error", "identity-service temporarily unavailable", "circuitOpen", true);
    }

    @CircuitBreaker(name = "identity-service", fallbackMethod = "refreshFallback")
    public Map<String, Object> refresh(String refreshToken) {
        try {
            var req = Map.of("refreshToken", refreshToken);
            return rpcIdentityService(RabbitTopology.RK_AUTH_REFRESH, req);
        } catch (Exception e) {
            log.error("refresh RPC failed: {}", e.getMessage(), e);
            return Map.of("error", e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private Map<String, Object> refreshFallback(String refreshToken, Throwable t) {
        log.warn("refresh circuit open or timed out: {}", t.getMessage());
        return Map.of("error", "identity-service temporarily unavailable", "circuitOpen", true);
    }

    @CircuitBreaker(name = "identity-service", fallbackMethod = "changePasswordFallback")
    public Map<String, Object> changePassword(UUID userId, String currentPassword, String newPassword) {
        try {
            var req = Map.of(
                    "userId", userId.toString(),
                    "currentPassword", currentPassword,
                    "newPassword", newPassword);
            return rpcIdentityService(RabbitTopology.RK_AUTH_CHANGE_PASSWORD, req);
        } catch (Exception e) {
            log.error("changePassword RPC failed: {}", e.getMessage(), e);
            return Map.of("error", e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private Map<String, Object> changePasswordFallback(UUID userId, String currentPassword,
                                                        String newPassword, Throwable t) {
        log.warn("changePassword circuit open or timed out: {}", t.getMessage());
        return Map.of("error", "identity-service temporarily unavailable", "circuitOpen", true);
    }

    @CircuitBreaker(name = "identity-service", fallbackMethod = "adminResetPasswordFallback")
    public Map<String, Object> adminResetPassword(UUID targetUserId, UUID tenantId, String newPassword) {
        try {
            var req = Map.of(
                    "userId",      targetUserId.toString(),
                    "tenantId",    tenantId.toString(),
                    "newPassword", newPassword);
            return rpcIdentityService(RabbitTopology.RK_USERS_CHANGE_PASSWORD, req);
        } catch (Exception e) {
            log.error("adminResetPassword RPC failed: {}", e.getMessage(), e);
            return Map.of("error", e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private Map<String, Object> adminResetPasswordFallback(UUID targetUserId, UUID tenantId,
                                                            String newPassword, Throwable t) {
        log.warn("adminResetPassword circuit open or timed out: {}", t.getMessage());
        return Map.of("error", "identity-service temporarily unavailable", "circuitOpen", true);
    }

    public void sendLogoutCommand(String refreshToken) {
        try {
            var envelope = new java.util.LinkedHashMap<String, Object>();
            envelope.put("command",      "LOGOUT");
            envelope.put("refreshToken", refreshToken);
            envelope.put("commandId",    UUID.randomUUID().toString());
            kafkaTemplate.send(KafkaTopics.AUTH_COMMANDS, "logout",
                    objectMapper.writeValueAsString(envelope));
            log.info("Logout command published");
        } catch (Exception e) {
            log.error("Failed to publish logout command: {}", e.getMessage(), e);
        }
    }

    // ─── User Queries (RabbitMQ) ──────────────────────────────────────────────

    @CircuitBreaker(name = "identity-service", fallbackMethod = "queryUsersFallback")
    public Map<String, Object> queryUsers(UUID tenantId, int page, int size) {
        try {
            var req = Map.of(
                    "tenantId", tenantId != null ? tenantId.toString() : "",
                    "page", page, "size", size);
            return rpcIdentityService(RabbitTopology.RK_USERS_QUERY, req);
        } catch (Exception e) {
            log.error("queryUsers failed: {}", e.getMessage(), e);
            return Map.of("error", e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private Map<String, Object> queryUsersFallback(UUID tenantId, int page, int size, Throwable t) {
        log.warn("queryUsers circuit open or timed out: {}", t.getMessage());
        return Map.of("error", "identity-service temporarily unavailable", "circuitOpen", true);
    }

    @CircuitBreaker(name = "identity-service", fallbackMethod = "getUserFallback")
    public Map<String, Object> getUser(UUID id, UUID tenantId) {
        try {
            var req = Map.of("id", id.toString(), "tenantId", tenantId.toString());
            return rpcIdentityService(RabbitTopology.RK_USERS_GET, req);
        } catch (Exception e) {
            log.error("getUser failed: {}", e.getMessage(), e);
            return Map.of("error", e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private Map<String, Object> getUserFallback(UUID id, UUID tenantId, Throwable t) {
        log.warn("getUser circuit open or timed out: {}", t.getMessage());
        return Map.of("error", "identity-service temporarily unavailable", "circuitOpen", true);
    }

    // ─── Tenant Queries (RabbitMQ) ────────────────────────────────────────────

    @CircuitBreaker(name = "identity-service", fallbackMethod = "listActiveWorkspacesFallback")
    public Map<String, Object> listActiveWorkspaces() {
        try {
            return rpcIdentityService(RabbitTopology.RK_TENANTS_LIST_ACTIVE, Map.of());
        } catch (Exception e) {
            log.error("listActiveWorkspaces failed: {}", e.getMessage(), e);
            return Map.of("error", e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private Map<String, Object> listActiveWorkspacesFallback(Throwable t) {
        log.warn("listActiveWorkspaces circuit open or timed out: {}", t.getMessage());
        return Map.of("error", "identity-service temporarily unavailable", "circuitOpen", true);
    }

    @CircuitBreaker(name = "identity-service", fallbackMethod = "queryTenantsFallback")
    public Map<String, Object> queryTenants(int page, int size) {
        try {
            var req = Map.of("page", page, "size", size);
            return rpcIdentityService(RabbitTopology.RK_TENANTS_QUERY, req);
        } catch (Exception e) {
            log.error("queryTenants failed: {}", e.getMessage(), e);
            return Map.of("error", e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private Map<String, Object> queryTenantsFallback(int page, int size, Throwable t) {
        log.warn("queryTenants circuit open or timed out: {}", t.getMessage());
        return Map.of("error", "identity-service temporarily unavailable", "circuitOpen", true);
    }

    @CircuitBreaker(name = "identity-service", fallbackMethod = "getTenantFallback")
    public Map<String, Object> getTenant(UUID id) {
        try {
            var req = Map.of("id", id.toString());
            return rpcIdentityService(RabbitTopology.RK_TENANTS_GET, req);
        } catch (Exception e) {
            log.error("getTenant failed: {}", e.getMessage(), e);
            return Map.of("error", e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private Map<String, Object> getTenantFallback(UUID id, Throwable t) {
        log.warn("getTenant circuit open or timed out: {}", t.getMessage());
        return Map.of("error", "identity-service temporarily unavailable", "circuitOpen", true);
    }

    // ─── Tenant Commands (RabbitMQ sync — tenant commands are rare admin actions) ─

    @CircuitBreaker(name = "identity-service", fallbackMethod = "tenantCommandFallback")
    public Map<String, Object> tenantCommand(String command, UUID tenantId, Map<String, Object> payload) {
        try {
            var req = new java.util.LinkedHashMap<String, Object>();
            req.put("command",  command);
            req.put("tenantId", tenantId != null ? tenantId.toString() : null);
            req.putAll(payload);
            return rpcIdentityService(RabbitTopology.RK_TENANTS_COMMAND, req);
        } catch (Exception e) {
            log.error("tenantCommand {} failed: {}", command, e.getMessage(), e);
            return Map.of("error", e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private Map<String, Object> tenantCommandFallback(String command, UUID tenantId,
                                                       Map<String, Object> payload, Throwable t) {
        log.warn("tenantCommand {} circuit open or timed out: {}", command, t.getMessage());
        return Map.of("error", "identity-service temporarily unavailable", "circuitOpen", true);
    }


    // ─── User Commands (Kafka) ────────────────────────────────────────────────

    public void sendUserCommand(String command, Map<String, Object> payload, UUID tenantId, String actor) {
        try {
            var envelope = new java.util.LinkedHashMap<String, Object>();
            envelope.put("command",     command);
            envelope.put("tenantId",    tenantId != null ? tenantId.toString() : null);
            envelope.put("requestedBy", actor);
            envelope.put("payload",     payload);
            envelope.put("commandId",   UUID.randomUUID().toString());
            kafkaTemplate.send(KafkaTopics.USER_COMMANDS, tenantId != null ? tenantId.toString() : "global",
                    objectMapper.writeValueAsString(envelope));
            log.info("User command published: command={} tenantId={} by={}", command, tenantId, actor);
        } catch (Exception e) {
            log.error("Failed to publish user command {}: {}", command, e.getMessage(), e);
            throw new RuntimeException("Failed to publish user command: " + command, e);
        }
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> rpcIdentityService(String routingKey, Object requestBody) throws Exception {
        String body = objectMapper.writeValueAsString(requestBody);
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        Message msg = MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                .andProperties(props).build();
        Message reply = rabbitTemplate.sendAndReceive(
                RabbitTopology.EXCHANGE_IDENTITY_SERVICE, routingKey, msg);
        if (reply == null) return Map.of("error", "identity-service unavailable");
        return objectMapper.readValue(
                new String(reply.getBody(), StandardCharsets.UTF_8), new TypeReference<>() {});
    }
}

