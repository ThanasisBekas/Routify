package io.routify.admin.controller;

import io.routify.admin.client.AuditMessagingClient;
import io.routify.common.event.QueryResponse;
import io.routify.common.web.RoutifyHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Admin Alerts Controller — dashboard CRUD for platform alert rules.
 *
 * <p>Alert queries and mutations go via RabbitMQ to routify-audit-service (sync RPC).
 * Alert rules are evaluated on a 60-second cycle by the audit-service's
 * {@code AlertEvaluationScheduler}.
 *
 * <p>Authorization:
 * <ul>
 *   <li>Read (GET): any authenticated user with AUDIT_READ or standard roles</li>
 *   <li>Write (POST/PUT/DELETE): TENANT_ADMIN or SUPER_ADMIN</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/alerts")
@RequiredArgsConstructor
public class AdminAlertsController {

    private final AuditMessagingClient messagingClient;

    @GetMapping
    @PreAuthorize("hasAuthority('AUDIT_READ') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<QueryResponse.AlertRulesPage> listAlerts(
            @RequestHeader(value = RoutifyHeaders.TENANT_ID, required = false) UUID tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(messagingClient.queryAlertRules(tenantId, page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('AUDIT_READ') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<QueryResponse.AlertRuleDetail> getAlert(
            @PathVariable UUID id,
            @RequestHeader(value = RoutifyHeaders.TENANT_ID, required = false) UUID tenantId) {
        return ResponseEntity.ok(messagingClient.getAlertRule(id, tenantId));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('GATEWAY_CONFIG_WRITE') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<QueryResponse.AlertRuleDetail> createAlert(
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        return ResponseEntity.ok(messagingClient.alertRuleCommand(
                "CREATE", tenantId, null,
                (String) body.get("name"),
                (String) body.get("description"),
                (String) body.get("metric"),
                body.get("routeId") != null ? UUID.fromString((String) body.get("routeId")) : null,
                (String) body.get("operator"),
                body.get("threshold") != null ? new BigDecimal(body.get("threshold").toString()) : null,
                body.get("windowMinutes") != null ? ((Number) body.get("windowMinutes")).intValue() : null,
                body.get("cooldownMinutes") != null ? ((Number) body.get("cooldownMinutes")).intValue() : null,
                (String) body.get("severity"),
                body.get("enabled") != null ? (Boolean) body.get("enabled") : null,
                null,
                auth.getName()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('GATEWAY_CONFIG_WRITE') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<QueryResponse.AlertRuleDetail> updateAlert(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        return ResponseEntity.ok(messagingClient.alertRuleCommand(
                "UPDATE", tenantId, id,
                (String) body.get("name"),
                (String) body.get("description"),
                (String) body.get("metric"),
                body.get("routeId") != null ? UUID.fromString((String) body.get("routeId")) : null,
                (String) body.get("operator"),
                body.get("threshold") != null ? new BigDecimal(body.get("threshold").toString()) : null,
                body.get("windowMinutes") != null ? ((Number) body.get("windowMinutes")).intValue() : null,
                body.get("cooldownMinutes") != null ? ((Number) body.get("cooldownMinutes")).intValue() : null,
                (String) body.get("severity"),
                body.get("enabled") != null ? (Boolean) body.get("enabled") : null,
                null,
                auth.getName()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('GATEWAY_CONFIG_WRITE') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<Void> deleteAlert(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            Authentication auth) {
        messagingClient.alertRuleCommand(
                "DELETE", tenantId, id,
                null, null, null, null, null, null, null, null, null, null, null, auth.getName());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/mute")
    @PreAuthorize("hasAuthority('GATEWAY_CONFIG_WRITE') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
    public ResponseEntity<QueryResponse.AlertRuleDetail> muteAlert(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        Integer minutes = body.get("durationMinutes") != null
                ? ((Number) body.get("durationMinutes")).intValue() : 60;
        return ResponseEntity.ok(messagingClient.alertRuleCommand(
                "MUTE", tenantId, id,
                null, null, null, null, null, null, null, null, null, null, minutes, auth.getName()));
    }

    @PostMapping("/{id}/unmute")
    @PreAuthorize("hasAuthority('GATEWAY_CONFIG_WRITE') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR')")
    public ResponseEntity<QueryResponse.AlertRuleDetail> unmuteAlert(
            @PathVariable UUID id,
            @RequestHeader(RoutifyHeaders.TENANT_ID) UUID tenantId,
            Authentication auth) {
        return ResponseEntity.ok(messagingClient.alertRuleCommand(
                "UNMUTE", tenantId, id,
                null, null, null, null, null, null, null, null, null, null, null, auth.getName()));
    }

    @GetMapping("/{id}/history")
    @PreAuthorize("hasAuthority('AUDIT_READ') or hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<QueryResponse.AlertEventsPage> getAlertHistory(
            @PathVariable UUID id,
            @RequestHeader(value = RoutifyHeaders.TENANT_ID, required = false) UUID tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(messagingClient.queryAlertEvents(id, tenantId, page, size));
    }
}

