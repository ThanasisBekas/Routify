package gr.routify.admin.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.admin.dto.CreateTenantRequest;
import gr.routify.admin.dto.CreateUserRequest;
import gr.routify.admin.dto.UpdateTenantRequest;
import gr.routify.admin.dto.UpdateUserRequest;
import gr.routify.common.client.AmqpServiceClientSupport;
import gr.routify.common.client.KafkaServiceClientSupport;
import gr.routify.common.domain.TenantPlan;
import gr.routify.common.domain.UserRole;
import gr.routify.common.event.CommandEvent;
import gr.routify.common.event.KafkaTopics;
import gr.routify.common.event.QueryRequest;
import gr.routify.common.event.QueryResponse;
import gr.routify.common.event.RabbitTopology;
import gr.routify.common.observability.RoutifyMetrics;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
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
                                   KafkaTemplate<String, Object> kafkaTemplate,
                                   RoutifyMetrics metrics) {
        super(rabbitTemplate, objectMapper, RabbitTopology.EXCHANGE_IDENTITY_SERVICE, "admin-api", metrics);
        this.kafka = new KafkaServiceClientSupport(kafkaTemplate, "admin-api") {};
    }

    // ─── Auth (RabbitMQ) ──────────────────────────────────────────────────────

    @CircuitBreaker(name = "identity-service", fallbackMethod = "loginFallback")
    public QueryResponse.LoginResult login(String username, String password, String tenantSlug) {
        return rpc(RabbitTopology.RK_AUTH_LOGIN,
                new QueryRequest.AuthLogin(username, password, tenantSlug),
                QueryResponse.LoginResult.class);
    }

    @SuppressWarnings("unused")
    private QueryResponse.LoginResult loginFallback(String username, String password,
                                                    String tenantSlug, Throwable t) {
        log.warn("login circuit open or timed out: {}", t.getMessage());
        return null;
    }

    @CircuitBreaker(name = "identity-service", fallbackMethod = "refreshFallback")
    public QueryResponse.LoginResult refresh(String refreshToken) {
        return rpc(RabbitTopology.RK_AUTH_REFRESH,
                new QueryRequest.AuthRefresh(refreshToken),
                QueryResponse.LoginResult.class);
    }

    @SuppressWarnings("unused")
    private QueryResponse.LoginResult refreshFallback(String refreshToken, Throwable t) {
        log.warn("refresh circuit open or timed out: {}", t.getMessage());
        return null;
    }

    @CircuitBreaker(name = "identity-service", fallbackMethod = "changePasswordFallback")
    public QueryResponse.PasswordChangeResult changePassword(UUID userId, String currentPassword, String newPassword) {
        return rpc(RabbitTopology.RK_AUTH_CHANGE_PASSWORD,
                new QueryRequest.AuthChangePassword(userId, currentPassword, newPassword),
                QueryResponse.PasswordChangeResult.class);
    }

    @SuppressWarnings("unused")
    private QueryResponse.PasswordChangeResult changePasswordFallback(UUID userId, String currentPassword,
                                                                      String newPassword, Throwable t) {
        log.warn("changePassword circuit open or timed out: {}", t.getMessage());
        return new QueryResponse.PasswordChangeResult(false);
    }

    @CircuitBreaker(name = "identity-service", fallbackMethod = "adminResetPasswordFallback")
    public QueryResponse.PasswordChangeResult adminResetPassword(UUID targetUserId, UUID tenantId, String newPassword) {
        return rpc(RabbitTopology.RK_USERS_CHANGE_PASSWORD,
                new QueryRequest.AdminResetPassword(targetUserId, tenantId, newPassword),
                QueryResponse.PasswordChangeResult.class);
    }

    @SuppressWarnings("unused")
    private QueryResponse.PasswordChangeResult adminResetPasswordFallback(UUID targetUserId, UUID tenantId,
                                                                          String newPassword, Throwable t) {
        log.warn("adminResetPassword circuit open or timed out: {}", t.getMessage());
        return new QueryResponse.PasswordChangeResult(false);
    }

    public void sendLogoutCommand(String refreshToken) {
        kafka.publishCommand(KafkaTopics.AUTH_COMMANDS,
                new CommandEvent.Logout(UUID.randomUUID(), null, "admin-api", Instant.now(), refreshToken));
    }

    // ─── User Queries (RabbitMQ) ──────────────────────────────────────────────

    @CircuitBreaker(name = "identity-service", fallbackMethod = "queryUsersFallback")
    public QueryResponse.UsersPage queryUsers(UUID tenantId, int page, int size) {
        return rpc(RabbitTopology.RK_USERS_QUERY,
                new QueryRequest.UsersQuery(tenantId, page, size),
                QueryResponse.UsersPage.class);
    }

    @SuppressWarnings("unused")
    private QueryResponse.UsersPage queryUsersFallback(UUID tenantId, int page, int size, Throwable t) {
        log.warn("queryUsers circuit open or timed out: {}", t.getMessage());
        return new QueryResponse.UsersPage(List.of(), 0L, 0, page, size);
    }

    @CircuitBreaker(name = "identity-service", fallbackMethod = "getUserFallback")
    public QueryResponse.UserDetail getUser(UUID id, UUID tenantId) {
        return rpc(RabbitTopology.RK_USERS_GET,
                new QueryRequest.UserGet(id, tenantId),
                QueryResponse.UserDetail.class);
    }

    @SuppressWarnings("unused")
    private QueryResponse.UserDetail getUserFallback(UUID id, UUID tenantId, Throwable t) {
        log.warn("getUser circuit open or timed out: {}", t.getMessage());
        return null;
    }

    // ─── Tenant Queries (RabbitMQ) ────────────────────────────────────────────

    @CircuitBreaker(name = "identity-service", fallbackMethod = "listActiveWorkspacesFallback")
    public QueryResponse.ActiveWorkspacesList listActiveWorkspaces() {
        return rpc(RabbitTopology.RK_TENANTS_LIST_ACTIVE,
                new QueryRequest.ListActiveWorkspaces(),
                QueryResponse.ActiveWorkspacesList.class);
    }

    @SuppressWarnings("unused")
    private QueryResponse.ActiveWorkspacesList listActiveWorkspacesFallback(Throwable t) {
        log.warn("listActiveWorkspaces circuit open or timed out: {}", t.getMessage());
        return new QueryResponse.ActiveWorkspacesList(List.of());
    }

    @CircuitBreaker(name = "identity-service", fallbackMethod = "queryTenantsFallback")
    public QueryResponse.TenantsPage queryTenants(int page, int size) {
        return rpc(RabbitTopology.RK_TENANTS_QUERY,
                new QueryRequest.TenantsQuery(page, size),
                QueryResponse.TenantsPage.class);
    }

    @SuppressWarnings("unused")
    private QueryResponse.TenantsPage queryTenantsFallback(int page, int size, Throwable t) {
        log.warn("queryTenants circuit open or timed out: {}", t.getMessage());
        return new QueryResponse.TenantsPage(List.of(), 0L, 0, page, size);
    }

    @CircuitBreaker(name = "identity-service", fallbackMethod = "getTenantFallback")
    public QueryResponse.TenantDetail getTenant(UUID id) {
        return rpc(RabbitTopology.RK_TENANTS_GET,
                new QueryRequest.TenantGet(id),
                QueryResponse.TenantDetail.class);
    }

    @SuppressWarnings("unused")
    private QueryResponse.TenantDetail getTenantFallback(UUID id, Throwable t) {
        log.warn("getTenant circuit open or timed out: {}", t.getMessage());
        return null;
    }

    // ─── Tenant Commands (RabbitMQ sync) ─────────────────────────────────────

    @CircuitBreaker(name = "identity-service", fallbackMethod = "createTenantFallback")
    public QueryResponse.TenantDetail createTenant(CreateTenantRequest req) {
        TenantPlan plan = parsePlan(req.plan(), TenantPlan.FREE);
        CommandEvent cmd = new CommandEvent.CreateTenant(
                UUID.randomUUID(), null, "admin-api", Instant.now(),
                req.name(), req.slug(), plan, req.contactEmail());
        return rpc(RabbitTopology.RK_TENANTS_COMMAND, cmd, QueryResponse.TenantDetail.class);
    }

    @SuppressWarnings("unused")
    private QueryResponse.TenantDetail createTenantFallback(CreateTenantRequest req, Throwable t) {
        log.warn("createTenant circuit open or timed out: {}", t.getMessage());
        return null;
    }

    @CircuitBreaker(name = "identity-service", fallbackMethod = "updateTenantFallback")
    public QueryResponse.TenantDetail updateTenant(UUID tenantId, UpdateTenantRequest req) {
        TenantPlan plan = parsePlan(req.plan(), null);
        CommandEvent cmd = new CommandEvent.UpdateTenant(
                UUID.randomUUID(), tenantId, "admin-api", Instant.now(),
                req.name(), plan, req.contactEmail());
        return rpc(RabbitTopology.RK_TENANTS_COMMAND, cmd, QueryResponse.TenantDetail.class);
    }

    @SuppressWarnings("unused")
    private QueryResponse.TenantDetail updateTenantFallback(UUID tenantId, UpdateTenantRequest req, Throwable t) {
        log.warn("updateTenant circuit open or timed out: {}", t.getMessage());
        return null;
    }

    @CircuitBreaker(name = "identity-service", fallbackMethod = "suspendTenantFallback")
    public QueryResponse.TenantDetail suspendTenant(UUID tenantId, String reason) {
        CommandEvent cmd = new CommandEvent.SuspendTenant(
                UUID.randomUUID(), tenantId, "admin-api", Instant.now(), reason);
        return rpc(RabbitTopology.RK_TENANTS_COMMAND, cmd, QueryResponse.TenantDetail.class);
    }

    @SuppressWarnings("unused")
    private QueryResponse.TenantDetail suspendTenantFallback(UUID tenantId, String reason, Throwable t) {
        log.warn("suspendTenant circuit open or timed out: {}", t.getMessage());
        return null;
    }

    @CircuitBreaker(name = "identity-service", fallbackMethod = "reactivateTenantFallback")
    public QueryResponse.TenantDetail reactivateTenant(UUID tenantId) {
        CommandEvent cmd = new CommandEvent.ReactivateTenant(
                UUID.randomUUID(), tenantId, "admin-api", Instant.now());
        return rpc(RabbitTopology.RK_TENANTS_COMMAND, cmd, QueryResponse.TenantDetail.class);
    }

    @SuppressWarnings("unused")
    private QueryResponse.TenantDetail reactivateTenantFallback(UUID tenantId, Throwable t) {
        log.warn("reactivateTenant circuit open or timed out: {}", t.getMessage());
        return null;
    }

    // ─── User Commands (Kafka) ────────────────────────────────────────────────

    public void sendCreateUser(UUID tenantId, String actor, CreateUserRequest req) {
        UserRole role = req.role() != null
                ? UserRole.valueOf(req.role().toUpperCase())
                : UserRole.VIEWER;
        kafka.publishCommand(KafkaTopics.USER_COMMANDS, new CommandEvent.CreateUser(
                UUID.randomUUID(), tenantId, actor, Instant.now(),
                req.username(), req.email(), req.password(), role));
    }

    public void sendUpdateUser(UUID id, UUID tenantId, String actor, UpdateUserRequest req) {
        UserRole role = req.role() != null
                ? UserRole.valueOf(req.role().toUpperCase())
                : null;
        kafka.publishCommand(KafkaTopics.USER_COMMANDS, new CommandEvent.UpdateUser(
                UUID.randomUUID(), tenantId, actor, Instant.now(),
                id, req.username(), req.email(), role));
    }

    public void sendDeleteUser(UUID id, UUID tenantId, String actor) {
        kafka.publishCommand(KafkaTopics.USER_COMMANDS,
                new CommandEvent.DeleteUser(UUID.randomUUID(), tenantId, actor, Instant.now(), id));
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private TenantPlan parsePlan(String planStr, TenantPlan defaultPlan) {
        if (planStr == null) return defaultPlan;
        try {
            return TenantPlan.valueOf(planStr.toUpperCase());
        } catch (IllegalArgumentException ex) {
            log.warn("Unknown plan '{}' — using default {}", planStr, defaultPlan);
            return defaultPlan;
        }
    }
}
