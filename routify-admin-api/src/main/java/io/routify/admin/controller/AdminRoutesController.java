package io.routify.admin.controller;

import io.routify.admin.client.RouteFilterMessagingClient;
import io.routify.admin.dto.AttachFilterRequest;
import io.routify.admin.dto.CreateRouteRequest;
import io.routify.admin.dto.UpdateRouteRequest;
import io.routify.common.event.QueryResponse;
import io.routify.common.web.AsyncAcknowledgement;
import io.routify.common.web.RoutifyHeaders;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Admin Routes Controller — dashboard CRUD for routes.
 *
 * <p>All read operations are served via RabbitMQ request/reply to routify-route-service.
 * All write operations are published as Kafka command events to routify-route-service.
 * There are NO direct HTTP calls to route-service.
 *
 * <p>Authorization:
 * <ul>
 *   <li>Read (GET): any authenticated user (VIEWER and above)</li>
 *   <li>Write (POST/PUT/DELETE): OPERATOR, TENANT_ADMIN, or SUPER_ADMIN</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/routes")
@RequiredArgsConstructor
public class AdminRoutesController {

    private final RouteFilterMessagingClient messagingClient;

    // ─── Queries ─────────────────────────────────────────────────────────────

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<QueryResponse.RoutesPage> listRoutes(
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String environment,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDir) {
        return ResponseEntity.ok(
                messagingClient.queryRoutes(tenantId, status, environment, page, size, sortBy, sortDir));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<QueryResponse.RouteDetail> getRoute(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId) {
        return ResponseEntity.ok(messagingClient.getRoute(id, tenantId));
    }

    // ─── Commands ────────────────────────────────────────────────────────────

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
    public ResponseEntity<AsyncAcknowledgement> createRoute(
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestHeader(value = RoutifyHeaders.USER_ID, required = false) String userId,
            @Valid @RequestBody CreateRouteRequest request,
            Authentication auth) {
        String actor = RoutifyHeaders.resolveActor(userId, auth != null ? auth.getName() : null);
        messagingClient.sendCreateRoute(tenantId, actor, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("Route creation in progress"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
    public ResponseEntity<AsyncAcknowledgement> updateRoute(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestHeader(value = RoutifyHeaders.USER_ID, required = false) String userId,
            @Valid @RequestBody UpdateRouteRequest request,
            Authentication auth) {
        String actor = RoutifyHeaders.resolveActor(userId, auth != null ? auth.getName() : null);
        messagingClient.sendUpdateRoute(id, tenantId, actor, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("Route update in progress"));
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
    public ResponseEntity<AsyncAcknowledgement> activateRoute(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestHeader(value = RoutifyHeaders.USER_ID, required = false) String userId,
            Authentication auth) {
        String actor = RoutifyHeaders.resolveActor(userId, auth != null ? auth.getName() : null);
        messagingClient.sendActivateRoute(id, tenantId, actor);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("Route activation in progress"));
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
    public ResponseEntity<AsyncAcknowledgement> deactivateRoute(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestHeader(value = RoutifyHeaders.USER_ID, required = false) String userId,
            Authentication auth) {
        String actor = RoutifyHeaders.resolveActor(userId, auth != null ? auth.getName() : null);
        messagingClient.sendDeactivateRoute(id, tenantId, actor);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("Route deactivation in progress"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
    public ResponseEntity<AsyncAcknowledgement> deleteRoute(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestHeader(value = RoutifyHeaders.USER_ID, required = false) String userId,
            Authentication auth) {
        String actor = RoutifyHeaders.resolveActor(userId, auth != null ? auth.getName() : null);
        messagingClient.sendDeleteRoute(id, tenantId, actor);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("Route deletion in progress"));
    }

    @PostMapping("/{id}/clone")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
    public ResponseEntity<QueryResponse.RouteDetail> cloneRoute(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestHeader(value = RoutifyHeaders.USER_ID, required = false) String userId,
            Authentication auth) {
        String actor = RoutifyHeaders.resolveActor(userId, auth != null ? auth.getName() : null);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(messagingClient.cloneRoute(id, tenantId, actor));
    }

    @PostMapping("/{id}/promote")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<AsyncAcknowledgement> promoteRoute(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestHeader(value = RoutifyHeaders.USER_ID, required = false) String userId,
            Authentication auth) {
        String actor = RoutifyHeaders.resolveActor(userId, auth != null ? auth.getName() : null);
        messagingClient.sendPromoteRoute(id, tenantId, actor);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("Route promotion in progress"));
    }

    // ─── Filter chain on route ────────────────────────────────────────────────

    @PostMapping("/{id}/filters")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
    public ResponseEntity<AsyncAcknowledgement> attachFilter(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestHeader(value = RoutifyHeaders.USER_ID, required = false) String userId,
            @Valid @RequestBody AttachFilterRequest request,
            Authentication auth) {
        String actor = RoutifyHeaders.resolveActor(userId, auth != null ? auth.getName() : null);
        String phase = request.phase() != null ? request.phase() : "PRE";
        messagingClient.sendAttachFilter(id, request.filterId(), request.order(), phase, tenantId, actor);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("Filter attach in progress"));
    }

    @DeleteMapping("/{id}/filters/{filterId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
    public ResponseEntity<AsyncAcknowledgement> detachFilter(
            @PathVariable UUID id,
            @PathVariable UUID filterId,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestHeader(value = RoutifyHeaders.USER_ID, required = false) String userId,
            Authentication auth) {
        String actor = RoutifyHeaders.resolveActor(userId, auth != null ? auth.getName() : null);
        messagingClient.sendDetachFilter(id, filterId, tenantId, actor);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("Filter detach in progress"));
    }
}
