package gr.routify.route.messaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.common.event.KafkaTopics;
import gr.routify.route.domain.FilterDefinition;
import gr.routify.route.domain.Route;
import gr.routify.route.service.FilterDefinitionService;
import gr.routify.route.service.RouteService;
import gr.routify.route.service.RouteUpdateCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Kafka command consumer for routify-route-service.
 *
 * <p>Consumes route and filter command events published by routify-admin-api.
 * Each command triggers the appropriate service method and the domain event
 * is published via the transactional outbox.
 *
 * <p>Command envelope format (JSON):
 * <pre>{@code
 * {
 *   "command":     "CREATE_ROUTE" | "UPDATE_ROUTE" | "ACTIVATE_ROUTE" |
 *                  "DEACTIVATE_ROUTE" | "DELETE_ROUTE" |
 *                  "ATTACH_FILTER" | "DETACH_FILTER",
 *   "tenantId":    "uuid",
 *   "requestedBy": "user-id or username",
 *   "commandId":   "uuid",
 *   "payload":     { ... command-specific fields ... }
 * }
 * }</pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouteCommandKafkaConsumer {

    private final RouteService            routeService;
    private final FilterDefinitionService filterService;
    private final ObjectMapper            objectMapper;

    @KafkaListener(
            topics = KafkaTopics.ROUTE_COMMANDS,
            groupId = "routify-route-service-commands",
            containerFactory = "routeCommandKafkaListenerContainerFactory"
    )
    public void onRouteCommand(String commandJson, Acknowledgment ack) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(commandJson, new TypeReference<>() {});
            String command    = str(envelope.get("command"));
            UUID   tenantId   = parseUuid(envelope.get("tenantId"));
            String requestedBy = str(envelope.get("requestedBy"));

            @SuppressWarnings("unchecked")
            Map<String, Object> payload = envelope.containsKey("payload")
                    ? (Map<String, Object>) envelope.get("payload")
                    : envelope;  // fallback: treat entire envelope as payload

            log.info("Route command received: command={} tenantId={} by={}", command, tenantId, requestedBy);

            switch (command) {
                case "CREATE_ROUTE"     -> executeCreateRoute(payload, tenantId, requestedBy);
                case "UPDATE_ROUTE"     -> executeUpdateRoute(payload, tenantId);
                case "ACTIVATE_ROUTE"   -> routeService.activate(parseUuid(payload.get("id")), tenantId);
                case "DEACTIVATE_ROUTE" -> routeService.deactivate(parseUuid(payload.get("id")), tenantId);
                case "DELETE_ROUTE"     -> routeService.delete(parseUuid(payload.get("id")), tenantId);
                case "ATTACH_FILTER"    -> executeAttachFilter(payload, tenantId);
                case "DETACH_FILTER"    -> routeService.detachFilter(
                        parseUuid(payload.get("routeId")), parseUuid(payload.get("filterId")), tenantId);
                default -> log.warn("Unknown route command: {}", command);
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process route command: {} — {}", commandJson, e.getMessage(), e);
            // Don't ack — let Kafka retry or route to DLQ
        }
    }

    @KafkaListener(
            topics = KafkaTopics.FILTER_COMMANDS,
            groupId = "routify-route-service-filter-commands",
            containerFactory = "routeCommandKafkaListenerContainerFactory"
    )
    public void onFilterCommand(String commandJson, Acknowledgment ack) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(commandJson, new TypeReference<>() {});
            String command    = str(envelope.get("command"));
            UUID   tenantId   = parseUuid(envelope.get("tenantId"));
            String requestedBy = str(envelope.get("requestedBy"));

            @SuppressWarnings("unchecked")
            Map<String, Object> payload = envelope.containsKey("payload")
                    ? (Map<String, Object>) envelope.get("payload")
                    : envelope;

            log.info("Filter command received: command={} tenantId={} by={}", command, tenantId, requestedBy);

            switch (command) {
                case "CREATE_FILTER" -> executeCreateFilter(payload, tenantId, requestedBy);
                case "UPDATE_FILTER" -> executeUpdateFilter(payload, tenantId);
                case "DELETE_FILTER" -> filterService.delete(parseUuid(payload.get("id")), tenantId);
                default -> log.warn("Unknown filter command: {}", command);
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process filter command: {} — {}", commandJson, e.getMessage(), e);
        }
    }

    // ─── Command executors ────────────────────────────────────────────────────

    private void executeCreateRoute(Map<String, Object> payload, UUID tenantId, String createdBy) {
        // methods is stored as comma-separated string in Route domain
        String methods     = payload.get("methods")     != null ? payload.get("methods").toString()     : "*";
        String stripPrefix = payload.get("stripPrefix") != null ? payload.get("stripPrefix").toString() : null;
        Route route = Route.builder()
                .tenantId(tenantId)
                .name(str(payload.get("name")))
                .description(str(payload.get("description")))
                .pathPattern(str(payload.get("pathPattern")))
                .methods(methods)
                .upstreamUri(str(payload.get("upstreamUri")))
                .stripPrefix(stripPrefix)
                .createdBy(createdBy)
                .extraConfig(parseMap(payload.get("extraConfig")))
                .build();
        routeService.create(route, tenantId, createdBy);
    }

    private void executeUpdateRoute(Map<String, Object> payload, UUID tenantId) {
        UUID id = parseUuid(payload.get("id"));
        // methods and stripPrefix are stored as strings in the command record
        String methods = payload.get("methods") != null ? payload.get("methods").toString() : null;
        String stripPrefix = payload.get("stripPrefix") != null ? payload.get("stripPrefix").toString() : null;
        var cmd = new RouteUpdateCommand(
                str(payload.get("name")),
                str(payload.get("description")),
                str(payload.get("pathPattern")),
                methods,
                str(payload.get("upstreamUri")),
                stripPrefix,
                parseMap(payload.get("extraConfig"))
        );
        routeService.update(id, tenantId, cmd);
    }

    private void executeAttachFilter(Map<String, Object> payload, UUID tenantId) {
        UUID routeId  = parseUuid(payload.get("routeId"));
        UUID filterId = parseUuid(payload.get("filterId"));
        int  order    = parseInt(payload.get("order"), 0);
        String phase  = str(payload.get("phase"), "PRE");
        routeService.attachFilter(routeId, filterId, order, phase, tenantId);
    }

    private void executeCreateFilter(Map<String, Object> payload, UUID tenantId, String createdBy) {
        FilterDefinition filter = FilterDefinition.builder()
                .tenantId(tenantId)
                .name(str(payload.get("name")))
                .description(str(payload.get("description")))
                .filterType(gr.routify.common.domain.FilterType.valueOf(
                        str(payload.get("filterType"), "CUSTOM").toUpperCase()))
                .config(parseMap(payload.get("config")))
                .createdBy(createdBy)
                .gatewayConfigRef(parseMap(payload.get("gatewayConfigRef")))
                .build();
        filterService.create(filter, tenantId);
    }

    private void executeUpdateFilter(Map<String, Object> payload, UUID tenantId) {
        UUID id = parseUuid(payload.get("id"));
        FilterDefinition existing = filterService.findById(id, tenantId);
        if (payload.get("name")             != null) existing.setName(str(payload.get("name")));
        if (payload.get("description")      != null) existing.setDescription(str(payload.get("description")));
        if (payload.get("config")           != null) existing.setConfig(parseMap(payload.get("config")));
        // Allow explicit null to clear the ref; use a sentinel key "gatewayConfigRef" presence to detect
        if (payload.containsKey("gatewayConfigRef")) {
            existing.setGatewayConfigRef(parseMap(payload.get("gatewayConfigRef")));
        }
        filterService.update(id, tenantId, existing);
    }

    private UUID parseUuid(Object val) {
        if (val == null || val.toString().isBlank()) return null;
        return UUID.fromString(val.toString());
    }

    private String str(Object val) {
        return val != null ? val.toString() : null;
    }

    private String str(Object val, String def) {
        return (val != null && !val.toString().isBlank()) ? val.toString() : def;
    }

    private int parseInt(Object val, int def) {
        try { return val != null ? Integer.parseInt(val.toString()) : def; }
        catch (Exception e) { return def; }
    }


    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMap(Object val) {
        if (val instanceof Map) return (Map<String, Object>) val;
        return null;
    }
}

