package io.routify.admin.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.routify.admin.config.CacheConfig;
import io.routify.admin.dto.CreateTenantRequest;
import io.routify.admin.dto.CreateUserRequest;
import io.routify.admin.dto.UpdateTenantRequest;
import io.routify.admin.dto.UpdateUserRequest;
import io.routify.common.client.AmqpServiceClientSupport;
import io.routify.common.client.KafkaServiceClientSupport;
import io.routify.common.domain.TenantPlan;
import io.routify.common.domain.UserRole;
import io.routify.common.event.CommandEvent;
import io.routify.common.event.KafkaTopics;
import io.routify.common.event.QueryRequest;
import io.routify.common.event.QueryResponse;
import io.routify.common.event.RabbitTopology;
import io.routify.common.observability.RoutifyMetrics;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
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

    @Cacheable(CacheConfig.CACHE_ACTIVE_WORKSPACES)
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

    @CacheEvict(value = CacheConfig.CACHE_ACTIVE_WORKSPACES, allEntries = true)
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

    @CacheEvict(value = CacheConfig.CACHE_ACTIVE_WORKSPACES, allEntries = true)
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

    @CacheEvict(value = CacheConfig.CACHE_ACTIVE_WORKSPACES, allEntries = true)
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

    @CacheEvict(value = CacheConfig.CACHE_ACTIVE_WORKSPACES, allEntries = true)
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

    // ─── API Key Queries (RabbitMQ sync) ──────────────────────────────────────

    @CircuitBreaker(name = "identity-service", fallbackMethod = "queryApiKeysFallback")
    public QueryResponse.ApiKeysPage queryApiKeys(UUID tenantId, int page, int size) {
        return rpc(RabbitTopology.RK_APIKEYS_QUERY,
                new QueryRequest.ApiKeysQuery(tenantId, page, size),
                QueryResponse.ApiKeysPage.class);
    }

    @SuppressWarnings("unused")
    private QueryResponse.ApiKeysPage queryApiKeysFallback(UUID tenantId, int page, int size, Throwable t) {
        log.warn("queryApiKeys circuit open or timed out: {}", t.getMessage());
        return new QueryResponse.ApiKeysPage(List.of(), 0L, 0, page, size);
    }

    @CircuitBreaker(name = "identity-service", fallbackMethod = "getApiKeyFallback")
    public QueryResponse.ApiKeyDetail getApiKey(UUID id, UUID tenantId) {
        return rpc(RabbitTopology.RK_APIKEYS_GET,
                new QueryRequest.ApiKeyGet(id, tenantId),
                QueryResponse.ApiKeyDetail.class);
    }

    @SuppressWarnings("unused")
    private QueryResponse.ApiKeyDetail getApiKeyFallback(UUID id, UUID tenantId, Throwable t) {
        log.warn("getApiKey circuit open or timed out: {}", t.getMessage());
        return null;
    }

    // ─── API Key Commands (RabbitMQ sync — raw key must be returned) ──────────

    @CircuitBreaker(name = "identity-service", fallbackMethod = "createApiKeyFallback")
    public QueryResponse.ApiKeyCreated createApiKey(UUID tenantId, UUID userId, String name,
                                                     String role, String email, String expiresAt, String actor) {
        return rpc(RabbitTopology.RK_APIKEYS_CREATE,
                new QueryRequest.ApiKeyCreate(tenantId, userId, name, role, email, expiresAt, actor),
                QueryResponse.ApiKeyCreated.class);
    }

    @SuppressWarnings("unused")
    private QueryResponse.ApiKeyCreated createApiKeyFallback(UUID tenantId, UUID userId, String name,
                                                              String role, String email, String expiresAt,
                                                              String actor, Throwable t) {
        log.warn("createApiKey circuit open or timed out: {}", t.getMessage());
        return null;
    }

    @CircuitBreaker(name = "identity-service", fallbackMethod = "revokeApiKeyFallback")
    public QueryResponse.ApiKeyDetail revokeApiKey(UUID id, UUID tenantId, String actor) {
        return rpc(RabbitTopology.RK_APIKEYS_REVOKE,
                new QueryRequest.ApiKeyRevoke(id, tenantId, actor),
                QueryResponse.ApiKeyDetail.class);
    }

    @SuppressWarnings("unused")
    private QueryResponse.ApiKeyDetail revokeApiKeyFallback(UUID id, UUID tenantId, String actor, Throwable t) {
        log.warn("revokeApiKey circuit open or timed out: {}", t.getMessage());
        return null;
    }

    @CircuitBreaker(name = "identity-service", fallbackMethod = "rotateApiKeyFallback")
    public QueryResponse.ApiKeyCreated rotateApiKey(UUID id, UUID tenantId, String actor) {
        return rpc(RabbitTopology.RK_APIKEYS_ROTATE,
                new QueryRequest.ApiKeyRotate(id, tenantId, actor),
                QueryResponse.ApiKeyCreated.class);
    }

    @SuppressWarnings("unused")
    private QueryResponse.ApiKeyCreated rotateApiKeyFallback(UUID id, UUID tenantId, String actor, Throwable t) {
        log.warn("rotateApiKey circuit open or timed out: {}", t.getMessage());
        return null;
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
