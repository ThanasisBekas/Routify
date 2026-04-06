package io.routify.identity.messaging;

import io.routify.common.domain.TenantPlan;
import io.routify.common.event.CommandEvent;
import io.routify.common.event.QueryRequest;
import io.routify.common.event.QueryResponse;
import io.routify.common.event.RabbitTopology;
import io.routify.common.exception.RoutifyException;
import io.routify.identity.domain.AppUser;
import io.routify.identity.domain.ApiKey;
import io.routify.identity.domain.Tenant;
import io.routify.identity.domain.WebhookDelivery;
import io.routify.identity.domain.WebhookSubscription;
import io.routify.identity.dto.AuthDto;
import io.routify.identity.service.ApiKeyService;
import io.routify.identity.service.AuthService;
import io.routify.identity.service.TenantService;
import io.routify.identity.service.UserService;
import io.routify.identity.service.WebhookDispatcher;
import io.routify.identity.service.WebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RabbitMQ request/reply handler for routify-identity-service.
 *
 * <p>All request bodies are deserialised into strongly-typed {@link QueryRequest}
 * or {@link CommandEvent} records by the Jackson2JsonMessageConverter.
 * Return values are serialised back to JSON automatically by the same converter.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdentityRabbitHandler {

    private final AuthService   authService;
    private final UserService   userService;
    private final TenantService tenantService;
    private final ApiKeyService apiKeyService;
    private final WebhookService webhookService;
    private final WebhookDispatcher webhookDispatcher;

    // ─── Auth ─────────────────────────────────────────────────────────────────

    @RabbitListener(queues = RabbitTopology.QUEUE_AUTH_LOGIN)
    public QueryResponse.LoginResult handleAuthLogin(QueryRequest.AuthLogin req) {
        log.debug("RabbitMQ: received auth.login request");
        try {
            AuthDto.LoginResponse resp = authService.login(
                    new AuthDto.LoginRequest(req.username(), req.password(), req.tenantSlug()));
            return toLoginResult(resp);
        } catch (RoutifyException e) {
            log.warn("auth.login rejected: {}", e.getMessage());
            throw new AmqpRejectAndDontRequeueException(e.getMessage(), e);
        }
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_AUTH_REFRESH)
    public QueryResponse.LoginResult handleAuthRefresh(QueryRequest.AuthRefresh req) {
        log.debug("RabbitMQ: received auth.refresh request");
        try {
            AuthDto.LoginResponse resp = authService.refresh(new AuthDto.RefreshRequest(req.refreshToken()));
            return toLoginResult(resp);
        } catch (RoutifyException e) {
            log.warn("auth.refresh rejected: {}", e.getMessage());
            throw new AmqpRejectAndDontRequeueException(e.getMessage(), e);
        }
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_AUTH_CHANGE_PASSWORD)
    public QueryResponse.PasswordChangeResult handleAuthChangePassword(QueryRequest.AuthChangePassword req) {
        log.debug("RabbitMQ: received auth.change-password request");
        try {
            authService.changePassword(req.userId(), req.currentPassword(), req.newPassword());
            return new QueryResponse.PasswordChangeResult(true);
        } catch (RoutifyException e) {
            log.warn("auth.change-password rejected: {}", e.getMessage());
            throw new AmqpRejectAndDontRequeueException(e.getMessage(), e);
        }
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_USERS_CHANGE_PASSWORD)
    public QueryResponse.PasswordChangeResult handleUsersChangePassword(QueryRequest.AdminResetPassword req) {
        log.debug("RabbitMQ: received users.change-password request");
        try {
            authService.adminResetPassword(req.userId(), req.tenantId(), req.newPassword());
            return new QueryResponse.PasswordChangeResult(true);
        } catch (RoutifyException e) {
            log.warn("users.change-password rejected: {}", e.getMessage());
            throw new AmqpRejectAndDontRequeueException(e.getMessage(), e);
        }
    }

    // ─── User Queries ─────────────────────────────────────────────────────────

    @RabbitListener(queues = RabbitTopology.QUEUE_USERS_QUERY)
    public QueryResponse.UsersPage handleUsersQuery(QueryRequest.UsersQuery req) {
        log.debug("RabbitMQ: received users.query request");
        try {
            Page<AppUser> result = userService.findAll(req.tenantId(), PageRequest.of(req.page(), req.size()));
            var content = result.getContent().stream().map(this::toUserSummary).toList();
            return new QueryResponse.UsersPage(content, result.getTotalElements(),
                    result.getTotalPages(), result.getNumber(), result.getSize());
        } catch (RoutifyException e) {
            log.warn("users.query rejected: {}", e.getMessage());
            throw new AmqpRejectAndDontRequeueException(e.getMessage(), e);
        }
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_USERS_GET)
    public QueryResponse.UserDetail handleUserGet(QueryRequest.UserGet req) {
        log.debug("RabbitMQ: received users.get request");
        try {
            return toUserDetail(userService.findById(req.id(), req.tenantId()));
        } catch (RoutifyException e) {
            log.warn("users.get rejected: {}", e.getMessage());
            throw new AmqpRejectAndDontRequeueException(e.getMessage(), e);
        }
    }

    // ─── Tenant Queries ───────────────────────────────────────────────────────

    @RabbitListener(queues = RabbitTopology.QUEUE_TENANTS_QUERY)
    public QueryResponse.TenantsPage handleTenantsQuery(QueryRequest.TenantsQuery req) {
        log.debug("RabbitMQ: received tenants.query request");
        try {
            Page<Tenant> result = tenantService.findAll(PageRequest.of(req.page(), req.size()));
            var content = result.getContent().stream().map(this::toTenantSummary).toList();
            return new QueryResponse.TenantsPage(content, result.getTotalElements(),
                    result.getTotalPages(), result.getNumber(), result.getSize());
        } catch (RoutifyException e) {
            log.warn("tenants.query rejected: {}", e.getMessage());
            throw new AmqpRejectAndDontRequeueException(e.getMessage(), e);
        }
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_TENANTS_GET)
    public QueryResponse.TenantDetail handleTenantGet(QueryRequest.TenantGet req) {
        log.debug("RabbitMQ: received tenants.get request");
        try {
            return toTenantDetail(tenantService.findById(req.id()));
        } catch (RoutifyException e) {
            log.warn("tenants.get rejected: {}", e.getMessage());
            throw new AmqpRejectAndDontRequeueException(e.getMessage(), e);
        }
    }

    /**
     * Returns a lightweight list of active workspaces (name + slug) for the
     * login-page dropdown.  This is intentionally minimal — no sensitive data.
     */
    @RabbitListener(queues = RabbitTopology.QUEUE_TENANTS_LIST_ACTIVE)
    public QueryResponse.ActiveWorkspacesList handleTenantsListActive(
            @SuppressWarnings("unused") QueryRequest.ListActiveWorkspaces request) {
        log.debug("RabbitMQ: received tenants.list-active request");
        try {
            List<QueryResponse.ActiveWorkspacesList.WorkspaceInfo> workspaces = tenantService
                    .findAll(PageRequest.of(0, 200))
                    .getContent()
                    .stream()
                    .filter(t -> t.getStatus() == Tenant.Status.ACTIVE)
                    .map(t -> new QueryResponse.ActiveWorkspacesList.WorkspaceInfo(t.getName(), t.getSlug()))
                    .toList();
            return new QueryResponse.ActiveWorkspacesList(workspaces);
        } catch (RoutifyException e) {
            log.warn("tenants.list-active rejected: {}", e.getMessage());
            throw new AmqpRejectAndDontRequeueException(e.getMessage(), e);
        }
    }

    // ─── Tenant Commands (sync — admin actions need immediate confirmation) ────

    @RabbitListener(queues = RabbitTopology.QUEUE_TENANTS_COMMAND)
    public QueryResponse.TenantDetail handleTenantCommand(CommandEvent command) {
        log.info("RabbitMQ: received tenants.command request");
        try {
            Tenant result = switch (command) {
                case CommandEvent.CreateTenant c -> {
                    TenantPlan plan = c.plan() != null ? c.plan() : TenantPlan.FREE;
                    yield tenantService.create(new AuthDto.CreateTenantRequest(
                            c.name(), c.slug(), plan, c.contactEmail()));
                }
                case CommandEvent.SuspendTenant c ->
                        tenantService.suspend(c.tenantId(),
                                c.reason() != null ? c.reason() : "Administrative action");
                case CommandEvent.ReactivateTenant c ->
                        tenantService.reactivate(c.tenantId());
                case CommandEvent.UpdateTenant c ->
                        tenantService.update(c.tenantId(), c.name(), c.plan(), c.contactEmail());
                default -> throw new IllegalArgumentException(
                        "Unexpected command type: " + command.getClass().getSimpleName());
            };
            return toTenantDetail(result);
        } catch (RoutifyException e) {
            log.warn("tenants.command rejected: {}", e.getMessage());
            throw new AmqpRejectAndDontRequeueException(e.getMessage(), e);
        }
    }

    // ─── API Key Queries ─────────────────────────────────────────────────────

    @RabbitListener(queues = RabbitTopology.QUEUE_APIKEYS_QUERY)
    public QueryResponse.ApiKeysPage handleApiKeysQuery(QueryRequest.ApiKeysQuery req) {
        log.debug("RabbitMQ: received apikeys.query request");
        try {
            var result = apiKeyService.findAll(req.tenantId(), PageRequest.of(req.page(), req.size()));
            var content = result.getContent().stream().map(this::toApiKeySummary).toList();
            return new QueryResponse.ApiKeysPage(content, result.getTotalElements(),
                    result.getTotalPages(), result.getNumber(), result.getSize());
        } catch (RoutifyException e) {
            log.warn("apikeys.query rejected: {}", e.getMessage());
            throw new AmqpRejectAndDontRequeueException(e.getMessage(), e);
        }
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_APIKEYS_GET)
    public QueryResponse.ApiKeyDetail handleApiKeyGet(QueryRequest.ApiKeyGet req) {
        log.debug("RabbitMQ: received apikeys.get request");
        try {
            return toApiKeyDetail(apiKeyService.findById(req.id(), req.tenantId()));
        } catch (RoutifyException e) {
            log.warn("apikeys.get rejected: {}", e.getMessage());
            throw new AmqpRejectAndDontRequeueException(e.getMessage(), e);
        }
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_APIKEYS_CREATE)
    public QueryResponse.ApiKeyCreated handleApiKeyCreate(QueryRequest.ApiKeyCreate req) {
        log.debug("RabbitMQ: received apikeys.create request");
        try {
            io.routify.common.domain.UserRole role = io.routify.common.domain.UserRole.valueOf(req.role());
            java.time.Instant expiresAt = req.expiresAt() != null ? java.time.Instant.parse(req.expiresAt()) : null;
            ApiKeyService.CreateResult result = apiKeyService.create(
                    req.tenantId(), req.userId(), req.name(), role, req.email(), expiresAt, req.actor());
            ApiKey key = result.apiKey();
            return new QueryResponse.ApiKeyCreated(
                    key.getId(), result.rawKey(), key.getKeyPrefix(),
                    key.getName(), key.getRole().name(), key.getExpiresAt(), key.getCreatedAt());
        } catch (RoutifyException e) {
            log.warn("apikeys.create rejected: {}", e.getMessage());
            throw new AmqpRejectAndDontRequeueException(e.getMessage(), e);
        }
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_APIKEYS_REVOKE)
    public QueryResponse.ApiKeyDetail handleApiKeyRevoke(QueryRequest.ApiKeyRevoke req) {
        log.debug("RabbitMQ: received apikeys.revoke request");
        try {
            apiKeyService.revoke(req.id(), req.tenantId(), req.actor());
            return toApiKeyDetail(apiKeyService.findById(req.id(), req.tenantId()));
        } catch (RoutifyException e) {
            log.warn("apikeys.revoke rejected: {}", e.getMessage());
            throw new AmqpRejectAndDontRequeueException(e.getMessage(), e);
        }
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_APIKEYS_ROTATE)
    public QueryResponse.ApiKeyCreated handleApiKeyRotate(QueryRequest.ApiKeyRotate req) {
        log.debug("RabbitMQ: received apikeys.rotate request");
        try {
            ApiKeyService.CreateResult result = apiKeyService.rotate(req.id(), req.tenantId(), req.actor());
            ApiKey key = result.apiKey();
            return new QueryResponse.ApiKeyCreated(
                    key.getId(), result.rawKey(), key.getKeyPrefix(),
                    key.getName(), key.getRole().name(), key.getExpiresAt(), key.getCreatedAt());
        } catch (RoutifyException e) {
            log.warn("apikeys.rotate rejected: {}", e.getMessage());
            throw new AmqpRejectAndDontRequeueException(e.getMessage(), e);
        }
    }

    // ─── Webhook Queries ────────────────────────────────────────────────────

    @RabbitListener(queues = RabbitTopology.QUEUE_WEBHOOKS_QUERY)
    public QueryResponse.WebhooksPage handleWebhooksQuery(QueryRequest.WebhooksQuery req) {
        log.debug("RabbitMQ: received webhooks.query request");
        try {
            var result = webhookService.findAll(req.tenantId(), PageRequest.of(req.page(), req.size()));
            var content = result.getContent().stream().map(this::toWebhookSummary).toList();
            return new QueryResponse.WebhooksPage(content, result.getTotalElements(),
                    result.getTotalPages(), result.getNumber(), result.getSize());
        } catch (RoutifyException e) {
            log.warn("webhooks.query rejected: {}", e.getMessage());
            throw new AmqpRejectAndDontRequeueException(e.getMessage(), e);
        }
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_WEBHOOKS_GET)
    public QueryResponse.WebhookDetail handleWebhookGet(QueryRequest.WebhookGet req) {
        log.debug("RabbitMQ: received webhooks.get request");
        try {
            return toWebhookDetail(webhookService.findById(req.id(), req.tenantId()));
        } catch (RoutifyException e) {
            log.warn("webhooks.get rejected: {}", e.getMessage());
            throw new AmqpRejectAndDontRequeueException(e.getMessage(), e);
        }
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_WEBHOOKS_DELIVERIES)
    public QueryResponse.WebhookDeliveriesPage handleWebhookDeliveries(QueryRequest.WebhookDeliveries req) {
        log.debug("RabbitMQ: received webhooks.deliveries request");
        try {
            // Validate subscription belongs to tenant
            webhookService.findById(req.subscriptionId(), req.tenantId());
            var result = webhookService.findDeliveries(req.subscriptionId(),
                    PageRequest.of(req.page(), req.size()));
            var content = result.getContent().stream().map(this::toDeliveryEntry).toList();
            return new QueryResponse.WebhookDeliveriesPage(content, result.getTotalElements(),
                    result.getTotalPages(), result.getNumber(), result.getSize());
        } catch (RoutifyException e) {
            log.warn("webhooks.deliveries rejected: {}", e.getMessage());
            throw new AmqpRejectAndDontRequeueException(e.getMessage(), e);
        }
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_WEBHOOKS_TEST)
    public QueryResponse.WebhookTestResult handleWebhookTest(QueryRequest.WebhookTest req) {
        log.debug("RabbitMQ: received webhooks.test request");
        try {
            WebhookSubscription sub = webhookService.findById(req.id(), req.tenantId());
            WebhookDispatcher.TestPingResult result = webhookDispatcher.testPing(sub);
            return new QueryResponse.WebhookTestResult(result.success(), result.responseStatus(), result.message());
        } catch (RoutifyException e) {
            log.warn("webhooks.test rejected: {}", e.getMessage());
            throw new AmqpRejectAndDontRequeueException(e.getMessage(), e);
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private QueryResponse.LoginResult toLoginResult(AuthDto.LoginResponse r) {
        QueryResponse.LoginResult.UserInfo user = null;
        if (r.user() != null) {
            user = new QueryResponse.LoginResult.UserInfo(
                    r.user().id(), r.user().tenantId(), r.user().username(),
                    r.user().email(), r.user().role(), r.user().mustChangePassword());
        }
        return new QueryResponse.LoginResult(
                r.accessToken(), r.refreshToken(), r.tokenType(),
                r.expiresIn(), r.mustChangePassword(), user);
    }

    private QueryResponse.UsersPage.UserSummary toUserSummary(AppUser u) {
        return new QueryResponse.UsersPage.UserSummary(
                u.getId(), u.getTenantId(), u.getUsername(), u.getEmail(),
                u.getRole(), u.getStatus().name(), u.isMustChangePassword(),
                u.getLastLoginAt(), u.getCreatedAt());
    }

    private QueryResponse.UserDetail toUserDetail(AppUser u) {
        return new QueryResponse.UserDetail(
                u.getId(), u.getTenantId(), u.getUsername(), u.getEmail(),
                u.getRole(), u.getStatus().name(), u.isMustChangePassword(),
                u.getLastLoginAt(), u.getCreatedAt());
    }

    private QueryResponse.TenantsPage.TenantSummary toTenantSummary(Tenant t) {
        return new QueryResponse.TenantsPage.TenantSummary(
                t.getId(), t.getName(), t.getSlug(),
                t.getStatus().name(), t.getPlan(), t.getContactEmail(), t.getCreatedAt());
    }

    private QueryResponse.TenantDetail toTenantDetail(Tenant t) {
        return new QueryResponse.TenantDetail(
                t.getId(), t.getName(), t.getSlug(),
                t.getStatus().name(), t.getPlan(), t.getContactEmail(), t.getCreatedAt());
    }

    private QueryResponse.ApiKeysPage.ApiKeySummary toApiKeySummary(ApiKey k) {
        return new QueryResponse.ApiKeysPage.ApiKeySummary(
                k.getId(), k.getTenantId(), k.getName(), k.getKeyPrefix(),
                k.getRole().name(), k.getEmail(), k.getStatus().name(),
                k.getExpiresAt(), k.getLastUsedAt(), k.getCreatedAt());
    }

    private QueryResponse.ApiKeyDetail toApiKeyDetail(ApiKey k) {
        return new QueryResponse.ApiKeyDetail(
                k.getId(), k.getTenantId(), k.getUserId(), k.getName(),
                k.getKeyPrefix(), k.getRole().name(), k.getEmail(),
                k.getStatus().name(), k.getExpiresAt(), k.getLastUsedAt(),
                k.getCreatedBy(), k.getCreatedAt(), k.getRevokedAt());
    }

    private QueryResponse.WebhooksPage.WebhookSummary toWebhookSummary(WebhookSubscription s) {
        return new QueryResponse.WebhooksPage.WebhookSummary(
                s.getId(), s.getTenantId(), s.getName(), s.getUrl(),
                s.getEventTypes(), s.getStatus().name(), s.getFailureCount(),
                s.getLastDeliveredAt(), s.getCreatedAt(), s.getUpdatedAt());
    }

    private QueryResponse.WebhookDetail toWebhookDetail(WebhookSubscription s) {
        return new QueryResponse.WebhookDetail(
                s.getId(), s.getTenantId(), s.getName(), s.getUrl(),
                s.getSecret(), s.getEventTypes(), s.getStatus().name(),
                s.getFailureCount(), s.getLastDeliveredAt(),
                s.getCreatedBy(), s.getCreatedAt(), s.getUpdatedAt());
    }

    private QueryResponse.WebhookDeliveriesPage.DeliveryEntry toDeliveryEntry(WebhookDelivery d) {
        return new QueryResponse.WebhookDeliveriesPage.DeliveryEntry(
                d.getId(), d.getSubscriptionId(), d.getEventType(),
                d.getPayload(), d.getResponseStatus(), d.getResponseBody(),
                d.getAttempt(), d.getStatus().name(), d.getDeliveredAt(),
                d.getNextRetryAt(), d.getErrorMessage(), d.getCreatedAt());
    }
}
