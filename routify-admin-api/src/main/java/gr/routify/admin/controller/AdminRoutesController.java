package gr.routify.admin.controller;

import gr.routify.admin.client.RouteFilterMessagingClient;
import gr.routify.common.event.QueryResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Admin Routes Controller — dashboard CRUD for routes.
 *
 * <p>All read operations are served via RabbitMQ request/reply to routify-route-service.
 * All write operations are published as Kafka command events to routify-route-service.
 * There are NO direct HTTP calls to route-service.
 *
 * <p>This is the single entry point for the dashboard to manage routes.
 */
@RestController
@RequestMapping("/api/v1/admin/routes")
@RequiredArgsConstructor
public class AdminRoutesController {

    private final RouteFilterMessagingClient messagingClient;

    @GetMapping
    public QueryResponse.RoutesPage listRoutes(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDir) {
        return messagingClient.queryRoutes(tenantId, status, page, size, sortBy, sortDir);
    }

    @GetMapping("/{id}")
    public QueryResponse.RouteDetail getRoute(
            @PathVariable UUID id,
            @RequestHeader("X-Tenant-Id") UUID tenantId) {
        return messagingClient.getRoute(id, tenantId);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createRoute(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @Valid @RequestBody Map<String, Object> request,
            Authentication auth) {
        String actor = userId != null ? userId : (auth != null ? auth.getName() : "system");
        messagingClient.sendCreateRoute(tenantId, actor, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("status", "accepted", "message", "Route creation in progress"));
    }

    @PutMapping("/{id}")
    public Map<String, Object> updateRoute(
            @PathVariable UUID id,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @Valid @RequestBody Map<String, Object> request,
            Authentication auth) {
        String actor = userId != null ? userId : (auth != null ? auth.getName() : "system");
        messagingClient.sendUpdateRoute(id, tenantId, actor, request);
        return Map.of("status", "accepted", "message", "Route update in progress");
    }

    @PostMapping("/{id}/activate")
    public Map<String, Object> activateRoute(
            @PathVariable UUID id,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            Authentication auth) {
        String actor = userId != null ? userId : (auth != null ? auth.getName() : "system");
        messagingClient.sendActivateRoute(id, tenantId, actor);
        return Map.of("status", "accepted", "message", "Route activation in progress");
    }

    @PostMapping("/{id}/deactivate")
    public Map<String, Object> deactivateRoute(
            @PathVariable UUID id,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            Authentication auth) {
        String actor = userId != null ? userId : (auth != null ? auth.getName() : "system");
        messagingClient.sendDeactivateRoute(id, tenantId, actor);
        return Map.of("status", "accepted", "message", "Route deactivation in progress");
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> deleteRoute(
            @PathVariable UUID id,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            Authentication auth) {
        String actor = userId != null ? userId : (auth != null ? auth.getName() : "system");
        messagingClient.sendDeleteRoute(id, tenantId, actor);
        return Map.of("status", "accepted", "message", "Route deletion in progress");
    }

    @PostMapping("/{id}/clone")
    public ResponseEntity<QueryResponse.RouteDetail> cloneRoute(
            @PathVariable UUID id,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            Authentication auth) {
        String actor = userId != null ? userId : (auth != null ? auth.getName() : "system");
        QueryResponse.RouteDetail cloned = messagingClient.cloneRoute(id, tenantId, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(cloned);
    }

    // ─── Filter chain on route ────────────────────────────────────────────────

    @PostMapping("/{id}/filters")
    public Map<String, Object> attachFilter(
            @PathVariable UUID id,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody Map<String, Object> request,
            Authentication auth) {
        String actor = userId != null ? userId : (auth != null ? auth.getName() : "system");
        UUID filterId = UUID.fromString(request.get("filterId").toString());
        int  order    = request.get("order") != null ? Integer.parseInt(request.get("order").toString()) : 0;
        String phase  = request.getOrDefault("phase", "PRE").toString();
        messagingClient.sendAttachFilter(id, filterId, order, phase, tenantId, actor);
        return Map.of("status", "accepted", "message", "Filter attach in progress");
    }

    @DeleteMapping("/{id}/filters/{filterId}")
    public Map<String, Object> detachFilter(
            @PathVariable UUID id,
            @PathVariable UUID filterId,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            Authentication auth) {
        String actor = userId != null ? userId : (auth != null ? auth.getName() : "system");
        messagingClient.sendDetachFilter(id, filterId, tenantId, actor);
        return Map.of("status", "accepted", "message", "Filter detach in progress");
    }
}
