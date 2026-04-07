package io.routify.admin.controller;

import io.routify.admin.client.AuditMessagingClient;
import io.routify.admin.client.RouteFilterMessagingClient;
import io.routify.admin.dto.AttachFilterRequest;
import io.routify.admin.dto.CreateRouteRequest;
import io.routify.admin.dto.UpdateRouteRequest;
import io.routify.admin.service.CanaryMonitorService;
import io.routify.common.event.QueryResponse;
import io.routify.common.observability.RoutifyMetrics;
import io.routify.common.web.AsyncAcknowledgement;
import io.routify.common.web.RoutifyHeaders;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
    private final AuditMessagingClient auditClient;
    private final CanaryMonitorService canaryMonitor;
    private final RoutifyMetrics metrics;

    // ─── Queries ─────────────────────────────────────────────────────────────

    @GetMapping
    @PreAuthorize("hasAuthority('ROUTES_READ') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
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
    @PreAuthorize("hasAuthority('ROUTES_READ') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<QueryResponse.RouteDetail> getRoute(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId) {
        return ResponseEntity.ok(messagingClient.getRoute(id, tenantId));
    }

    // ─── Commands ────────────────────────────────────────────────────────────

    @PostMapping
    @PreAuthorize("hasAuthority('ROUTES_WRITE') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
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
    @PreAuthorize("hasAuthority('ROUTES_WRITE') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
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
    @PreAuthorize("hasAuthority('ROUTES_ACTIVATE') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
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
    @PreAuthorize("hasAuthority('ROUTES_ACTIVATE') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
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
    @PreAuthorize("hasAuthority('ROUTES_DELETE') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
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
    @PreAuthorize("hasAuthority('ROUTES_WRITE') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
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
    @PreAuthorize("hasAuthority('ROUTES_PROMOTE') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
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
    @PreAuthorize("hasAuthority('FILTERS_WRITE') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
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
    @PreAuthorize("hasAuthority('FILTERS_WRITE') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
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

    // ─── Cache Management ─────────────────────────────────────────────────────

    @PostMapping("/{id}/cache/purge")
    @PreAuthorize("hasAuthority('ROUTES_WRITE') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
    public ResponseEntity<AsyncAcknowledgement> purgeCache(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestHeader(value = RoutifyHeaders.USER_ID, required = false) String userId,
            Authentication auth) {
        String actor = RoutifyHeaders.resolveActor(userId, auth != null ? auth.getName() : null);
        messagingClient.sendPurgeCacheRoute(id, tenantId, actor);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("Cache purge in progress"));
    }

    // ─── Canary Routing ───────────────────────────────────────────────────────

    @PostMapping("/{id}/canary")
    @PreAuthorize("hasAuthority('ROUTES_WRITE') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
    public ResponseEntity<AsyncAcknowledgement> deployCanary(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestHeader(value = RoutifyHeaders.USER_ID, required = false) String userId,
            @RequestBody Map<String, Object> request,
            Authentication auth) {
        String actor = RoutifyHeaders.resolveActor(userId, auth != null ? auth.getName() : null);
        String canaryUpstreamUri = (String) request.get("canaryUpstreamUri");
        int trafficWeight = request.get("trafficWeight") instanceof Number n ? n.intValue() : 10;
        double autoRollbackThreshold = request.get("autoRollbackThreshold") instanceof Number n ? n.doubleValue() : 5.0;
        @SuppressWarnings("unchecked")
        Map<String, Object> canaryExtraConfig = request.get("canaryExtraConfig") instanceof Map m ? m : null;

        messagingClient.sendDeployCanary(id, tenantId, actor,
                canaryUpstreamUri, trafficWeight, autoRollbackThreshold, canaryExtraConfig);
        metrics.recordCanaryDeployment();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("Canary deployment in progress"));
    }

    @GetMapping("/{id}/canary/status")
    @PreAuthorize("hasAuthority('ROUTES_READ') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<QueryResponse.CanaryStatusResult> getCanaryStatus(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId) {
        // Compose canary status from route detail + audit health data
        QueryResponse.RouteDetail primary = messagingClient.getRoute(id, tenantId);
        if (primary == null || primary.canaryRouteId() == null) {
            return ResponseEntity.notFound().build();
        }

        QueryResponse.RouteDetail canary = messagingClient.getRoute(primary.canaryRouteId(), tenantId);

        // Query error rates from audit-service
        double primaryErrorRate = 0.0;
        double canaryErrorRate = 0.0;
        try {
            QueryResponse.RouteHealthResponse health = auditClient.queryRouteHealth(tenantId, "5m");
            if (health != null && health.routes() != null) {
                primaryErrorRate = health.routes().stream()
                        .filter(r -> r.routeId().equals(id))
                        .findFirst()
                        .map(r -> r.errorRate() * 100.0)
                        .orElse(0.0);
                canaryErrorRate = health.routes().stream()
                        .filter(r -> r.routeId().equals(primary.canaryRouteId()))
                        .findFirst()
                        .map(r -> r.errorRate() * 100.0)
                        .orElse(0.0);
            }
        } catch (Exception e) {
            // Health data unavailable — return zeros
        }

        // Trigger canary health check for auto-rollback monitoring
        if (primary.canaryAutoRollbackThreshold() != null) {
            canaryMonitor.checkCanary(tenantId, id, primary.canaryRouteId(),
                    primary.canaryAutoRollbackThreshold().doubleValue());
        }

        return ResponseEntity.ok(new QueryResponse.CanaryStatusResult(
                id,
                primary.canaryRouteId(),
                primary.trafficWeight(),
                canary != null ? canary.trafficWeight() : 0,
                canary != null ? canary.upstreamUri() : "",
                primary.canaryAutoRollbackThreshold() != null ? primary.canaryAutoRollbackThreshold().doubleValue() : 0.0,
                primaryErrorRate,
                canaryErrorRate,
                canary != null && canary.activatedAt() != null ? canary.activatedAt().toString() : null,
                canaryMonitor.getBreachCount(id)));
    }

    @PostMapping("/{id}/canary/promote")
    @PreAuthorize("hasAuthority('ROUTES_WRITE') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
    public ResponseEntity<AsyncAcknowledgement> promoteCanary(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestHeader(value = RoutifyHeaders.USER_ID, required = false) String userId,
            Authentication auth) {
        String actor = RoutifyHeaders.resolveActor(userId, auth != null ? auth.getName() : null);
        messagingClient.sendPromoteCanary(id, tenantId, actor);
        canaryMonitor.stopTracking(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("Canary promotion in progress"));
    }

    @PostMapping("/{id}/canary/rollback")
    @PreAuthorize("hasAuthority('ROUTES_WRITE') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
    public ResponseEntity<AsyncAcknowledgement> rollbackCanary(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestHeader(value = RoutifyHeaders.USER_ID, required = false) String userId,
            @RequestBody(required = false) Map<String, Object> request,
            Authentication auth) {
        String actor = RoutifyHeaders.resolveActor(userId, auth != null ? auth.getName() : null);
        String reason = request != null && request.get("reason") instanceof String r ? r : "Manual rollback";
        messagingClient.sendRollbackCanary(id, tenantId, actor, reason);
        metrics.recordCanaryRollback();
        canaryMonitor.stopTracking(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("Canary rollback in progress"));
    }

    @PutMapping("/{id}/canary/weight")
    @PreAuthorize("hasAuthority('ROUTES_WRITE') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
    public ResponseEntity<AsyncAcknowledgement> adjustCanaryWeight(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestHeader(value = RoutifyHeaders.USER_ID, required = false) String userId,
            @RequestBody Map<String, Object> request,
            Authentication auth) {
        String actor = RoutifyHeaders.resolveActor(userId, auth != null ? auth.getName() : null);
        int newWeight = request.get("weight") instanceof Number n ? n.intValue() : 10;
        messagingClient.sendAdjustCanaryWeight(id, tenantId, actor, newWeight);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("Canary weight adjustment in progress"));
    }

    // ─── Circuit Breaker Manual Override ──────────────────────────────────────

    @PostMapping("/{id}/circuit-breaker/force-open")
    @PreAuthorize("hasAuthority('ROUTES_WRITE') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
    public ResponseEntity<AsyncAcknowledgement> forceCircuitBreakerOpen(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestHeader(value = RoutifyHeaders.USER_ID, required = false) String userId,
            Authentication auth) {
        String actor = RoutifyHeaders.resolveActor(userId, auth != null ? auth.getName() : null);
        messagingClient.sendForceCircuitBreaker(id, tenantId, actor, "FORCE_OPEN");
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("Circuit breaker force-open in progress"));
    }

    @PostMapping("/{id}/circuit-breaker/force-closed")
    @PreAuthorize("hasAuthority('ROUTES_WRITE') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
    public ResponseEntity<AsyncAcknowledgement> forceCircuitBreakerClosed(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestHeader(value = RoutifyHeaders.USER_ID, required = false) String userId,
            Authentication auth) {
        String actor = RoutifyHeaders.resolveActor(userId, auth != null ? auth.getName() : null);
        messagingClient.sendForceCircuitBreaker(id, tenantId, actor, "FORCE_CLOSED");
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("Circuit breaker force-closed in progress"));
    }

    @PostMapping("/{id}/circuit-breaker/reset")
    @PreAuthorize("hasAuthority('ROUTES_WRITE') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
    public ResponseEntity<AsyncAcknowledgement> resetCircuitBreaker(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestHeader(value = RoutifyHeaders.USER_ID, required = false) String userId,
            Authentication auth) {
        String actor = RoutifyHeaders.resolveActor(userId, auth != null ? auth.getName() : null);
        messagingClient.sendForceCircuitBreaker(id, tenantId, actor, "RESET");
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("Circuit breaker reset in progress"));
    }
}
