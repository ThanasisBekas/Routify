package io.routify.admin.controller;

import io.routify.admin.client.IdentityMessagingClient;
import io.routify.admin.dto.CreateWebhookRequest;
import io.routify.admin.dto.UpdateWebhookRequest;
import io.routify.common.event.QueryResponse;
import io.routify.common.web.AsyncAcknowledgement;
import io.routify.common.web.RoutifyHeaders;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Admin Webhooks Controller — CRUD for webhook subscriptions, delivery log, and test ping.
 *
 * <p>Writes (create/update/delete) are dispatched as Kafka commands (HTTP 202).
 * Reads (list/get/deliveries) use synchronous RabbitMQ request/reply.
 * Test ping is a synchronous RPC call.
 */
@RestController
@RequestMapping("/api/v1/admin/webhooks")
@RequiredArgsConstructor
public class AdminWebhooksController {

    private final IdentityMessagingClient messagingClient;

    @GetMapping
    @PreAuthorize("hasAuthority('WEBHOOKS_READ') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<QueryResponse.WebhooksPage> listWebhooks(
            @RequestHeader(value = RoutifyHeaders.TENANT_ID, required = false) UUID tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(messagingClient.queryWebhooks(tenantId, page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('WEBHOOKS_READ') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<QueryResponse.WebhookDetail> getWebhook(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId) {
        return ResponseEntity.ok(messagingClient.getWebhook(id, tenantId));
    }

    /**
     * Create a new webhook subscription. Returns HTTP 202 — subscription created asynchronously.
     */
    @PostMapping
    @PreAuthorize("hasAuthority('WEBHOOKS_ADMIN') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<AsyncAcknowledgement> createWebhook(
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @Valid @RequestBody CreateWebhookRequest request,
            Authentication auth) {
        String actor = RoutifyHeaders.resolveActor(null, auth != null ? auth.getName() : null);
        messagingClient.sendCreateWebhook(tenantId, actor, request.name(), request.url(), request.eventTypes());
        return ResponseEntity.accepted().body(AsyncAcknowledgement.of("Webhook subscription creation in progress"));
    }

    /**
     * Update an existing webhook subscription. Returns HTTP 202.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('WEBHOOKS_ADMIN') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<AsyncAcknowledgement> updateWebhook(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestBody UpdateWebhookRequest request,
            Authentication auth) {
        String actor = RoutifyHeaders.resolveActor(null, auth != null ? auth.getName() : null);
        messagingClient.sendUpdateWebhook(id, tenantId, actor, request.name(), request.url(), request.eventTypes());
        return ResponseEntity.accepted().body(AsyncAcknowledgement.of("Webhook subscription update in progress"));
    }

    /**
     * Delete a webhook subscription. Returns HTTP 202.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('WEBHOOKS_ADMIN') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<AsyncAcknowledgement> deleteWebhook(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            Authentication auth) {
        String actor = RoutifyHeaders.resolveActor(null, auth != null ? auth.getName() : null);
        messagingClient.sendDeleteWebhook(id, tenantId, actor);
        return ResponseEntity.accepted().body(AsyncAcknowledgement.of("Webhook subscription deletion in progress"));
    }

    /**
     * Send a test ping to a webhook subscription — synchronous, returns result inline.
     */
    @PostMapping("/{id}/test")
    @PreAuthorize("hasAuthority('WEBHOOKS_ADMIN') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<QueryResponse.WebhookTestResult> testWebhook(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId) {
        return ResponseEntity.ok(messagingClient.testWebhook(id, tenantId));
    }

    /**
     * Delivery log for a specific subscription.
     */
    @GetMapping("/{id}/deliveries")
    @PreAuthorize("hasAuthority('WEBHOOKS_READ') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<QueryResponse.WebhookDeliveriesPage> getDeliveries(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(messagingClient.queryWebhookDeliveries(id, tenantId, page, size));
    }
}

