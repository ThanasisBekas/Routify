package io.routify.admin.controller;

import io.routify.admin.client.RouteFilterMessagingClient;
import io.routify.admin.dto.CreateFilterRequest;
import io.routify.admin.dto.UpdateFilterRequest;
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
 * Admin Filters Controller — dashboard CRUD for filter definitions.
 *
 * <p>Read operations go via RabbitMQ to routify-route-service.
 * Write operations are published as Kafka command events to routify-route-service.
 *
 * <p>Authorization:
 * <ul>
 *   <li>Read (GET): any authenticated user (VIEWER and above)</li>
 *   <li>Write (POST/PUT/DELETE): OPERATOR, TENANT_ADMIN, or SUPER_ADMIN</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/filters")
@RequiredArgsConstructor
public class AdminFiltersController {

    private final RouteFilterMessagingClient messagingClient;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<QueryResponse.FiltersPage> listFilters(
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDir) {
        return ResponseEntity.ok(
                messagingClient.queryFilters(tenantId, page, size, sortBy, sortDir));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<QueryResponse.FilterDetail> getFilter(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId) {
        return ResponseEntity.ok(messagingClient.getFilter(id, tenantId));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
    public ResponseEntity<AsyncAcknowledgement> createFilter(
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestHeader(value = RoutifyHeaders.USER_ID, required = false) String userId,
            @Valid @RequestBody CreateFilterRequest request,
            Authentication auth) {
        String actor = RoutifyHeaders.resolveActor(userId, auth != null ? auth.getName() : null);
        messagingClient.sendCreateFilter(tenantId, actor, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("Filter creation in progress"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
    public ResponseEntity<AsyncAcknowledgement> updateFilter(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestHeader(value = RoutifyHeaders.USER_ID, required = false) String userId,
            @Valid @RequestBody UpdateFilterRequest request,
            Authentication auth) {
        String actor = RoutifyHeaders.resolveActor(userId, auth != null ? auth.getName() : null);
        messagingClient.sendUpdateFilter(id, tenantId, actor, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("Filter update in progress"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
    public ResponseEntity<AsyncAcknowledgement> deleteFilter(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestHeader(value = RoutifyHeaders.USER_ID, required = false) String userId,
            Authentication auth) {
        String actor = RoutifyHeaders.resolveActor(userId, auth != null ? auth.getName() : null);
        messagingClient.sendDeleteFilter(id, tenantId, actor);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AsyncAcknowledgement.of("Filter deletion in progress"));
    }
}
