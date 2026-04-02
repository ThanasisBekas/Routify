package gr.routify.identity.messaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.common.domain.TenantPlan;
import gr.routify.common.event.RabbitTopology;
import gr.routify.identity.domain.AppUser;
import gr.routify.identity.domain.Tenant;
import gr.routify.identity.dto.AuthDto;
import gr.routify.identity.service.AuthService;
import gr.routify.identity.service.TenantService;
import gr.routify.identity.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RabbitMQ request/reply handler for routify-identity-service.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdentityRabbitHandler {

    private final AuthService   authService;
    private final UserService   userService;
    private final TenantService tenantService;
    private final ObjectMapper  objectMapper;

    // ─── Auth ─────────────────────────────────────────────────────────────────

    @RabbitListener(queues = RabbitTopology.QUEUE_AUTH_LOGIN)
    public String handleAuthLogin(String requestBody) {
        log.debug("RabbitMQ: received auth.login request");
        try {
            Map<String, Object> req = objectMapper.readValue(requestBody, new TypeReference<>() {});
            var loginReq = new AuthDto.LoginRequest(
                    str(req.get("username")),
                    str(req.get("password")),
                    str(req.get("tenantSlug")));
            AuthDto.LoginResponse resp = authService.login(loginReq);
            return objectMapper.writeValueAsString(loginResponseToMap(resp));
        } catch (Exception e) {
            log.warn("RabbitMQ: auth.login failed: {}", e.getMessage());
            return errorJson(e);
        }
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_AUTH_REFRESH)
    public String handleAuthRefresh(String requestBody) {
        log.debug("RabbitMQ: received auth.refresh request");
        try {
            Map<String, Object> req = objectMapper.readValue(requestBody, new TypeReference<>() {});
            var refreshReq = new AuthDto.RefreshRequest(str(req.get("refreshToken")));
            AuthDto.LoginResponse resp = authService.refresh(refreshReq);
            return objectMapper.writeValueAsString(loginResponseToMap(resp));
        } catch (Exception e) {
            log.warn("RabbitMQ: auth.refresh failed: {}", e.getMessage());
            return errorJson(e);
        }
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_AUTH_CHANGE_PASSWORD)
    public String handleAuthChangePassword(String requestBody) {
        log.debug("RabbitMQ: received auth.change-password request");
        try {
            Map<String, Object> req = objectMapper.readValue(requestBody, new TypeReference<>() {});
            java.util.UUID userId = parseUuid(req.get("userId"));
            authService.changePassword(userId, str(req.get("currentPassword")), str(req.get("newPassword")));
            return objectMapper.writeValueAsString(Map.of("success", true));
        } catch (Exception e) {
            log.warn("RabbitMQ: auth.change-password failed: {}", e.getMessage());
            return errorJson(e);
        }
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_USERS_CHANGE_PASSWORD)
    public String handleUsersChangePassword(String requestBody) {
        log.debug("RabbitMQ: received users.change-password request");
        try {
            Map<String, Object> req = objectMapper.readValue(requestBody, new TypeReference<>() {});
            java.util.UUID targetUserId = parseUuid(req.get("userId"));
            java.util.UUID tenantId     = parseUuid(req.get("tenantId"));
            authService.adminResetPassword(targetUserId, tenantId, str(req.get("newPassword")));
            return objectMapper.writeValueAsString(Map.of("success", true));
        } catch (Exception e) {
            log.warn("RabbitMQ: users.change-password failed: {}", e.getMessage());
            return errorJson(e);
        }
    }

    // ─── User Queries ─────────────────────────────────────────────────────────

    @RabbitListener(queues = RabbitTopology.QUEUE_USERS_QUERY)
    public String handleUsersQuery(String requestBody) {
        log.debug("RabbitMQ: received users.query request");
        try {
            Map<String, Object> req = objectMapper.readValue(requestBody, new TypeReference<>() {});
            UUID tenantId = parseUuid(req.get("tenantId"));
            int  page     = parseInt(req.get("page"), 0);
            int  size     = parseInt(req.get("size"), 20);

            Page<AppUser> result = userService.findAll(tenantId, PageRequest.of(page, size));

            Map<String, Object> response = new HashMap<>();
            response.put("content",       result.getContent().stream().map(this::userToMap).toList());
            response.put("totalElements", result.getTotalElements());
            response.put("totalPages",    result.getTotalPages());
            response.put("page",          result.getNumber());
            response.put("size",          result.getSize());
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("RabbitMQ: users.query failed: {}", e.getMessage(), e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_USERS_GET)
    public String handleUserGet(String requestBody) {
        log.debug("RabbitMQ: received users.get request");
        try {
            Map<String, Object> req = objectMapper.readValue(requestBody, new TypeReference<>() {});
            UUID id       = parseUuid(req.get("id"));
            UUID tenantId = parseUuid(req.get("tenantId"));
            AppUser user = userService.findById(id, tenantId);
            return objectMapper.writeValueAsString(userToMap(user));
        } catch (Exception e) {
            log.error("RabbitMQ: users.get failed: {}", e.getMessage(), e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ─── Tenant Queries ───────────────────────────────────────────────────────

    @RabbitListener(queues = RabbitTopology.QUEUE_TENANTS_QUERY)
    public String handleTenantsQuery(String requestBody) {
        log.debug("RabbitMQ: received tenants.query request");
        try {
            Map<String, Object> req = objectMapper.readValue(requestBody, new TypeReference<>() {});
            int page = parseInt(req.get("page"), 0);
            int size = parseInt(req.get("size"), 20);

            Page<Tenant> result = tenantService.findAll(PageRequest.of(page, size));

            Map<String, Object> response = new HashMap<>();
            response.put("content",       result.getContent().stream().map(this::tenantToMap).toList());
            response.put("totalElements", result.getTotalElements());
            response.put("totalPages",    result.getTotalPages());
            response.put("page",          result.getNumber());
            response.put("size",          result.getSize());
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("RabbitMQ: tenants.query failed: {}", e.getMessage(), e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_TENANTS_GET)
    public String handleTenantGet(String requestBody) {
        log.debug("RabbitMQ: received tenants.get request");
        try {
            Map<String, Object> req = objectMapper.readValue(requestBody, new TypeReference<>() {});
            UUID id = parseUuid(req.get("id"));
            Tenant tenant = tenantService.findById(id);
            return objectMapper.writeValueAsString(tenantToMap(tenant));
        } catch (Exception e) {
            log.error("RabbitMQ: tenants.get failed: {}", e.getMessage(), e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    /**
     * Returns a lightweight list of active workspaces (name + slug) for the
     * login-page dropdown.  This is intentionally minimal — no sensitive data.
     */
    @RabbitListener(queues = RabbitTopology.QUEUE_TENANTS_LIST_ACTIVE)
    public String handleTenantsListActive(String requestBody) {
        log.debug("RabbitMQ: received tenants.list-active request");
        try {
            List<Map<String, Object>> workspaces = tenantService
                    .findAll(PageRequest.of(0, 200))
                    .getContent()
                    .stream()
                    .filter(t -> t.getStatus() == Tenant.Status.ACTIVE)
                    .map(t -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("name", t.getName());
                        m.put("slug", t.getSlug());
                        return m;
                    })
                    .toList();
            return objectMapper.writeValueAsString(Map.of("workspaces", workspaces));
        } catch (Exception e) {
            log.error("RabbitMQ: tenants.list-active failed: {}", e.getMessage(), e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ─── Tenant Commands (sync — admin actions need immediate confirmation) ────

    @RabbitListener(queues = RabbitTopology.QUEUE_TENANTS_COMMAND)
    public String handleTenantCommand(String requestBody) {
        log.info("RabbitMQ: received tenants.command request");
        try {
            Map<String, Object> req = objectMapper.readValue(requestBody, new TypeReference<>() {});
            String command  = str(req.get("command"));
            UUID   tenantId = parseUuid(req.get("tenantId"));

            Tenant result = switch (command) {
                case "CREATE_TENANT" -> {
                    // Default to FREE if plan is missing or blank — "BASIC" is not a valid TenantPlan.
                    String planStr = str(req.get("plan"));
                    TenantPlan plan;
                    try {
                        plan = (planStr != null && !planStr.isBlank())
                                ? TenantPlan.valueOf(planStr.toUpperCase())
                                : TenantPlan.FREE;
                    } catch (IllegalArgumentException ex) {
                        log.warn("Unknown plan '{}' — defaulting to FREE", planStr);
                        plan = TenantPlan.FREE;
                    }
                    var createReq = new AuthDto.CreateTenantRequest(
                            str(req.get("name")),
                            str(req.get("slug")),
                            plan,
                            str(req.get("contactEmail")));
                    yield tenantService.create(createReq);
                }
                case "SUSPEND_TENANT"    -> tenantService.suspend(tenantId, str(req.getOrDefault("reason", "Administrative action")));
                case "REACTIVATE_TENANT" -> tenantService.reactivate(tenantId);
                case "UPDATE_TENANT"     -> {
                    String planStr = str(req.get("plan"));
                    TenantPlan plan = null;
                    if (planStr != null && !planStr.isBlank()) {
                        try { plan = TenantPlan.valueOf(planStr.toUpperCase()); }
                        catch (IllegalArgumentException ex) { log.warn("Unknown plan '{}' — ignoring", planStr); }
                    }
                    yield tenantService.update(tenantId, str(req.get("name")), plan, str(req.get("contactEmail")));
                }
                default -> throw new IllegalArgumentException("Unknown tenant command: " + command);
            };

            return objectMapper.writeValueAsString(tenantToMap(result));
        } catch (Exception e) {
            log.error("RabbitMQ: tenants.command failed: {}", e.getMessage(), e);
            return errorJson(e);
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> loginResponseToMap(AuthDto.LoginResponse r) {
        Map<String, Object> m = new HashMap<>();
        m.put("accessToken",       r.accessToken());
        m.put("refreshToken",      r.refreshToken());
        m.put("tokenType",         r.tokenType());
        m.put("expiresIn",         r.expiresIn());
        m.put("mustChangePassword",r.mustChangePassword());
        if (r.user() != null) {
            Map<String, Object> u = new HashMap<>();
            u.put("id",                r.user().id());
            u.put("tenantId",          r.user().tenantId());
            u.put("username",          r.user().username());
            u.put("email",             r.user().email());
            u.put("role",              r.user().role() != null ? r.user().role().name() : null);
            u.put("mustChangePassword",r.user().mustChangePassword());
            m.put("user", u);
        }
        return m;
    }

    private String errorJson(Exception e) {
        String code = e.getClass().getSimpleName();
        try {
            return objectMapper.writeValueAsString(Map.of("error", e.getMessage(), "code", code));
        } catch (Exception ex) {
            return "{\"error\":\"internal error\"}";
        }
    }

    private Map<String, Object> userToMap(AppUser u) {
        Map<String, Object> m = new HashMap<>();
        m.put("id",                u.getId());
        m.put("tenantId",          u.getTenantId());
        m.put("username",          u.getUsername());
        m.put("email",             u.getEmail());
        m.put("role",              u.getRole().name());
        m.put("status",            u.getStatus().name());
        m.put("mustChangePassword",u.isMustChangePassword());
        m.put("lastLoginAt",       u.getLastLoginAt() != null ? u.getLastLoginAt().toString() : null);
        m.put("createdAt",         u.getCreatedAt() != null ? u.getCreatedAt().toString() : null);
        return m;
    }

    private Map<String, Object> tenantToMap(Tenant t) {
        Map<String, Object> m = new HashMap<>();
        m.put("id",           t.getId());
        m.put("name",         t.getName());
        m.put("slug",         t.getSlug());
        m.put("status",       t.getStatus().name());
        m.put("plan",         t.getPlan().name());
        m.put("contactEmail", t.getContactEmail());
        m.put("createdAt",    t.getCreatedAt() != null ? t.getCreatedAt().toString() : null);
        return m;
    }

    private UUID parseUuid(Object val) {
        if (val == null || val.toString().isBlank()) return null;
        return UUID.fromString(val.toString());
    }

    private int parseInt(Object val, int def) {
        try { return val != null ? Integer.parseInt(val.toString()) : def; } catch (Exception e) { return def; }
    }

    private String str(Object val) {
        return val != null ? val.toString() : null;
    }
}

