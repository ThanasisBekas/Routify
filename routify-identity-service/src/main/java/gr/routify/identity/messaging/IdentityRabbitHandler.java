package gr.routify.identity.messaging;

import gr.routify.common.domain.TenantPlan;
import gr.routify.common.event.CommandEvent;
import gr.routify.common.event.QueryRequest;
import gr.routify.common.event.QueryResponse;
import gr.routify.common.event.RabbitTopology;
import gr.routify.common.exception.RoutifyException;
import gr.routify.identity.domain.AppUser;
import gr.routify.identity.domain.Tenant;
import gr.routify.identity.dto.AuthDto;
import gr.routify.identity.service.AuthService;
import gr.routify.identity.service.TenantService;
import gr.routify.identity.service.UserService;
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
}
