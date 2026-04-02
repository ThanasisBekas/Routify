package gr.routify.admin.controller;

import gr.routify.admin.client.RouteFilterMessagingClient;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Admin Filters Controller — dashboard CRUD for filter definitions.
 *
 * <p>Read operations go via RabbitMQ to routify-route-service.
 * Write operations are published as Kafka command events to routify-route-service.
 */
@RestController
@RequestMapping("/api/v1/admin/filters")
@RequiredArgsConstructor
public class AdminFiltersController {

    private final RouteFilterMessagingClient messagingClient;

    @GetMapping
    public Map<String, Object> listFilters(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDir) {
        return messagingClient.queryFilters(tenantId, page, size, sortBy, sortDir);
    }

    @GetMapping("/{id}")
    public Map<String, Object> getFilter(
            @PathVariable UUID id,
            @RequestHeader("X-Tenant-Id") UUID tenantId) {
        return messagingClient.getFilter(id, tenantId);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createFilter(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @Valid @RequestBody Map<String, Object> request,
            Authentication auth) {
        String actor = userId != null ? userId : (auth != null ? auth.getName() : "system");
        messagingClient.sendCreateFilter(tenantId, actor, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("status", "accepted", "message", "Filter creation in progress"));
    }

    @PutMapping("/{id}")
    public Map<String, Object> updateFilter(
            @PathVariable UUID id,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @Valid @RequestBody Map<String, Object> request,
            Authentication auth) {
        String actor = userId != null ? userId : (auth != null ? auth.getName() : "system");
        messagingClient.sendUpdateFilter(id, tenantId, actor, request);
        return Map.of("status", "accepted", "message", "Filter update in progress");
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> deleteFilter(
            @PathVariable UUID id,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            Authentication auth) {
        String actor = userId != null ? userId : (auth != null ? auth.getName() : "system");
        messagingClient.sendDeleteFilter(id, tenantId, actor);
        return Map.of("status", "accepted", "message", "Filter deletion in progress");
    }
}

