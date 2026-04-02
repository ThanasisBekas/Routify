package gr.routify.admin.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.common.client.AmqpServiceClientSupport;
import gr.routify.common.client.KafkaServiceClientSupport;
import gr.routify.common.domain.TenantPlan;
import gr.routify.common.domain.UserRole;
import gr.routify.common.event.CommandEvent;
import gr.routify.common.event.KafkaTopics;
import gr.routify.common.event.QueryRequest;
import gr.routify.common.event.RabbitTopology;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
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
public class IdentityMessagingClient extends AmqpServiceClientSupport {

    private final KafkaServiceClientSupport kafka;

    public IdentityMessagingClient(RabbitTemplate rabbitTemplate,
                                   ObjectMapper objectMapper,
                                   KafkaTemplate<String, String> kafkaTemplate) {
        super(rabbitTemplate, objectMapper, RabbitTopology.EXCHANGE_IDENTITY_SERVICE, "admin-api");
        this.kafka = new KafkaServiceClientSupport(kafkaTemplate, objectMapper, "admin-api") {};
    }

    // ─── Auth (RabbitMQ) ──────────────────────────────────────────────────────

    @CircuitBreaker(name = "identity-service", fallbackMethod = "loginFallback")
    public Map<String, Object> login(String username, String password, String tenantSlug) {
        try {
            return rpc(RabbitTopology.RK_AUTH_LOGIN,
                    new QueryRequest.AuthLogin(username, password, tenantSlug));
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
            return rpc(RabbitTopology.RK_AUTH_REFRESH, new QueryRequest.AuthRefresh(refreshToken));
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
            return rpc(RabbitTopology.RK_AUTH_CHANGE_PASSWORD,
                    new QueryRequest.AuthChangePassword(userId, currentPassword, newPassword));
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
            return rpc(RabbitTopology.RK_USERS_CHANGE_PASSWORD,
                    new QueryRequest.AdminResetPassword(targetUserId, tenantId, newPassword));
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
        kafka.publishCommand(KafkaTopics.AUTH_COMMANDS,
                new CommandEvent.Logout(UUID.randomUUID(), null, "admin-api", Instant.now(), refreshToken));
    }

    // ─── User Queries (RabbitMQ) ──────────────────────────────────────────────

    @CircuitBreaker(name = "identity-service", fallbackMethod = "queryUsersFallback")
    public Map<String, Object> queryUsers(UUID tenantId, int page, int size) {
        try {
            return rpc(RabbitTopology.RK_USERS_QUERY,
                    new QueryRequest.UsersQuery(tenantId, page, size));
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
            return rpc(RabbitTopology.RK_USERS_GET, new QueryRequest.UserGet(id, tenantId));
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
            return rpc(RabbitTopology.RK_TENANTS_LIST_ACTIVE, new QueryRequest.ListActiveWorkspaces());
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
            return rpc(RabbitTopology.RK_TENANTS_QUERY, new QueryRequest.TenantsQuery(page, size));
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
            return rpc(RabbitTopology.RK_TENANTS_GET, new QueryRequest.TenantGet(id));
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

    // ─── Tenant Commands (RabbitMQ sync) ─────────────────────────────────────

    @CircuitBreaker(name = "identity-service", fallbackMethod = "tenantCommandFallback")
    public Map<String, Object> tenantCommand(String command, UUID tenantId, Map<String, Object> payload) {
        try {
            CommandEvent cmd = buildTenantCommand(command, tenantId, payload);
            return rpc(RabbitTopology.RK_TENANTS_COMMAND, cmd);
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

    public void sendCreateUser(UUID tenantId, String actor, Map<String, Object> req) {
        UserRole role = req.get("role") != null
                ? UserRole.valueOf(req.get("role").toString().toUpperCase())
                : UserRole.VIEWER;
        kafka.publishCommand(KafkaTopics.USER_COMMANDS, new CommandEvent.CreateUser(
                UUID.randomUUID(), tenantId, actor, Instant.now(),
                str(req, "username"), str(req, "email"),
                str(req, "password"), role));
    }

    public void sendUpdateUser(UUID id, UUID tenantId, String actor, Map<String, Object> req) {
        UserRole role = req.get("role") != null
                ? UserRole.valueOf(req.get("role").toString().toUpperCase())
                : null;
        kafka.publishCommand(KafkaTopics.USER_COMMANDS, new CommandEvent.UpdateUser(
                UUID.randomUUID(), tenantId, actor, Instant.now(),
                id, str(req, "username"), str(req, "email"), role));
    }

    public void sendDeleteUser(UUID id, UUID tenantId, String actor) {
        kafka.publishCommand(KafkaTopics.USER_COMMANDS,
                new CommandEvent.DeleteUser(UUID.randomUUID(), tenantId, actor, Instant.now(), id));
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private CommandEvent buildTenantCommand(String command, UUID tenantId, Map<String, Object> p) {
        return switch (command) {
            case "CREATE_TENANT" -> {
                TenantPlan plan = TenantPlan.FREE;
                if (p.get("plan") != null) {
                    try { plan = TenantPlan.valueOf(p.get("plan").toString().toUpperCase()); }
                    catch (IllegalArgumentException ex) {
                        log.warn("Unknown plan '{}' — defaulting to FREE", p.get("plan"));
                    }
                }
                yield new CommandEvent.CreateTenant(UUID.randomUUID(), null, "admin-api", Instant.now(),
                        str(p, "name"), str(p, "slug"), plan, str(p, "contactEmail"));
            }
            case "UPDATE_TENANT" -> {
                TenantPlan plan = null;
                if (p.get("plan") != null) {
                    try { plan = TenantPlan.valueOf(p.get("plan").toString().toUpperCase()); }
                    catch (IllegalArgumentException ex) {
                        log.warn("Unknown plan '{}' — ignoring", p.get("plan"));
                    }
                }
                yield new CommandEvent.UpdateTenant(UUID.randomUUID(), tenantId, "admin-api",
                        Instant.now(), str(p, "name"), plan, str(p, "contactEmail"));
            }
            case "SUSPEND_TENANT" -> new CommandEvent.SuspendTenant(
                    UUID.randomUUID(), tenantId, "admin-api", Instant.now(),
                    p.getOrDefault("reason", "Administrative action").toString());
            case "REACTIVATE_TENANT" -> new CommandEvent.ReactivateTenant(
                    UUID.randomUUID(), tenantId, "admin-api", Instant.now());
            default -> throw new IllegalArgumentException("Unknown tenant command: " + command);
        };
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : null;
    }
}

