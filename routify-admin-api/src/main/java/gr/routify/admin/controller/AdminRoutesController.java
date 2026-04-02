package gr.routify.admin.controller;

import gr.routify.admin.client.RouteFilterMessagingClient;
import gr.routify.common.event.QueryResponse;
import gr.routify.common.web.AsyncAcknowledgement;
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

    // ─── Queries ─────────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<QueryResponse.RoutesPage> listRoutes(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDir) {
        return ResponseEntity.ok(
                messagingClient.queryRoutes(tenantId, status, page, size, sortBy, sortDir));
    }

    @GetMapping("/{id}")
    public ResponseEntity<QueryResponse.RouteDetail> getRoute(
            @PathVariable UUID id,
            @RequestHeader("X-Tenant-Id") UUID tenantId) {
        return ResponseEntity.ok(messagingClient.getRoute(id, tenantId));
    }

    // ─── Commands ────────────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<AsyncAcknowledgement> createRoute(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @Valid @RequestBody Map<String, Object> request,
            Authentication auth) {
        String actor = resolveActor(userId, auth);
        messagingClient.sendCreateRoute(tenantId, actor, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("Route creation in progress"));
    }

    @PutMapping("/{id}")
    public ResponseEntity<AsyncAcknowledgement> updateRoute(
            @PathVariable UUID id,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @Valid @RequestBody Map<String, Object> request,
            Authentication auth) {
        String actor = resolveActor(userId, auth);
        messagingClient.sendUpdateRoute(id, tenantId, actor, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("Route update in progress"));
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<AsyncAcknowledgement> activateRoute(
            @PathVariable UUID id,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            Authentication auth) {
        String actor = resolveActor(userId, auth);
        messagingClient.sendActivateRoute(id, tenantId, actor);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("Route activation in progress"));
    }

    @PostMapping("/{id}/deactivate")
    public ResponseEntity<AsyncAcknowledgement> deactivateRoute(
            @PathVariable UUID id,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            Authentication auth) {
        String actor = resolveActor(userId, auth);
        messagingClient.sendDeactivateRoute(id, tenantId, actor);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("Route deactivation in progress"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<AsyncAcknowledgement> deleteRoute(
            @PathVariable UUID id,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            Authentication auth) {
        String actor = resolveActor(userId, auth);
        messagingClient.sendDeleteRoute(id, tenantId, actor);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("Route deletion in progress"));
    }

    @PostMapping("/{id}/clone")
    public ResponseEntity<QueryResponse.RouteDetail> cloneRoute(
            @PathVariable UUID id,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            Authentication auth) {
        String actor = resolveActor(userId, auth);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(messagingClient.cloneRoute(id, tenantId, actor));
    }

    // ─── Filter chain on route ────────────────────────────────────────────────

    @PostMapping("/{id}/filters")
    public ResponseEntity<AsyncAcknowledgement> attachFilter(
            @PathVariable UUID id,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody Map<String, Object> request,
            Authentication auth) {
        String actor   = resolveActor(userId, auth);
        UUID filterId  = UUID.fromString(request.get("filterId").toString());
        int  order     = request.get("order") != null ? Integer.parseInt(request.get("order").toString()) : 0;
        String phase   = request.getOrDefault("phase", "PRE").toString();
        messagingClient.sendAttachFilter(id, filterId, order, phase, tenantId, actor);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("Filter attach in progress"));
    }

    @DeleteMapping("/{id}/filters/{filterId}")
    public ResponseEntity<AsyncAcknowledgement> detachFilter(
            @PathVariable UUID id,
            @PathVariable UUID filterId,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            Authentication auth) {
        String actor = resolveActor(userId, auth);
        messagingClient.sendDetachFilter(id, filterId, tenantId, actor);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("Filter detach in progress"));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static String resolveActor(String userId, Authentication auth) {
        if (userId != null && !userId.isBlank()) return userId;
        return auth != null ? auth.getName() : "system";
    }
}
