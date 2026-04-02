package gr.routify.route.messaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.common.event.RabbitTopology;
import gr.routify.common.domain.RouteStatus;
import gr.routify.route.mapper.RouteMapper;
import gr.routify.route.service.FilterDefinitionService;
import gr.routify.route.service.GatewayConfigService;
import gr.routify.route.service.RouteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import gr.routify.route.dto.RouteStatusCount;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * RabbitMQ request/reply handler for routify-route-service.
 *
 * <p>Responds to synchronous queries from other services — primarily from
 * routify-admin-api (the sole dashboard backend):
 * <ul>
 *   <li><b>Gateway snapshot</b>: routify-api-gateway on startup and forced refresh.</li>
 *   <li><b>Gateway config GET/SAVE</b>: routify-admin-api gateway config management.</li>
 *   <li><b>Route stats</b>: routify-admin-api dashboard overview.</li>
 *   <li><b>Routes query / get</b>: routify-admin-api dashboard route list and detail.</li>
 *   <li><b>Filters query / get</b>: routify-admin-api dashboard filter list and detail.</li>
 * </ul>
 *
 * <p>Write operations (create/update/delete route/filter) arrive as Kafka command
 * events on {@code routify.route.commands} and {@code routify.filter.commands} topics,
 * consumed by {@code RouteCommandKafkaConsumer}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouteServiceRabbitHandler {

    private final RouteService             routeService;
    private final FilterDefinitionService  filterService;
    private final GatewayConfigService     gatewayConfigService;
    private final RouteMapper              routeMapper;
    private final ObjectMapper             objectMapper;

    @RabbitListener(queues = RabbitTopology.QUEUE_ROUTE_GATEWAY_SNAPSHOT)
    public String handleGatewaySnapshotRequest(@SuppressWarnings("unused") String requestBody) {
        log.debug("RabbitMQ: received gateway snapshot request");
        try {
            var snapshots = routeService.findAllActiveWithFilters()
                    .stream().map(routeMapper::toGatewaySnapshot).toList();
            log.debug("RabbitMQ: returning {} active routes in snapshot", snapshots.size());
            return objectMapper.writeValueAsString(snapshots);
        } catch (Exception e) {
            log.error("RabbitMQ: failed to build gateway snapshot: {}", e.getMessage(), e);
            return "[]";
        }
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_GATEWAY_CONFIG_GET)
    public String handleGatewayConfigGet(@SuppressWarnings("unused") String requestBody) {
        log.debug("RabbitMQ: received gateway config GET request");
        try {
            return objectMapper.writeValueAsString(gatewayConfigService.getGlobalConfig());
        } catch (Exception e) {
            log.error("RabbitMQ: failed to get gateway config: {}", e.getMessage(), e);
            return "{}";
        }
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_GATEWAY_CONFIG_SAVE)
    public String handleGatewayConfigSave(
            String requestBody,
            @Header(value = RabbitTopology.HEADER_CHANGED_BY,    required = false) String changedBy,
            @Header(value = RabbitTopology.HEADER_CONFIG_SECTION, required = false) String section) {
        log.info("RabbitMQ: gateway config SAVE: section={} by={}", section, changedBy);
        try {
            Map<String, Object> configMap = objectMapper.readValue(requestBody, new TypeReference<>() {});
            Map<String, Object> saved = gatewayConfigService.saveGlobalConfig(
                    configMap,
                    changedBy != null ? changedBy : "system",
                    section   != null ? section   : "full");
            return objectMapper.writeValueAsString(saved);
        } catch (Exception e) {
            log.error("RabbitMQ: failed to save gateway config: {}", e.getMessage(), e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_ROUTE_STATS)
    public String handleRouteStats(String requestBody) {
        log.debug("RabbitMQ: received route stats request");
        try {
            Map<String, Object> stats = new HashMap<>();
            UUID tenantId = null;

            if (requestBody != null && !requestBody.isBlank() && !requestBody.equals("{}")) {
                Map<String, Object> req = objectMapper.readValue(requestBody, new TypeReference<>() {});
                Object tid = req.get("tenantId");
                if (tid != null && !tid.toString().isBlank()) tenantId = UUID.fromString(tid.toString());
            }

            if (tenantId != null) {
                var page = routeService.findAll(tenantId, null, PageRequest.of(0, 1));
                stats.put("total",    page.getTotalElements());
                stats.put("tenantId", tenantId.toString());
            }

            for (RouteStatusCount row : routeService.countByStatus(tenantId)) {
                stats.put(row.status().name().toLowerCase(), row.count());
            }

            return objectMapper.writeValueAsString(stats);
        } catch (Exception e) {
            log.error("RabbitMQ: failed to build route stats: {}", e.getMessage(), e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ─── Admin-API: Route Queries ─────────────────────────────────────────────

    @RabbitListener(queues = RabbitTopology.QUEUE_ROUTES_QUERY)
    public String handleRoutesQuery(String requestBody) {
        log.debug("RabbitMQ: received routes.query request");
        try {
            Map<String, Object> req = objectMapper.readValue(requestBody, new TypeReference<>() {});
            UUID tenantId = parseUuid(req.get("tenantId"));
            String statusStr = str(req.get("status"));
            RouteStatus status = (statusStr != null && !statusStr.isBlank())
                    ? RouteStatus.valueOf(statusStr.toUpperCase()) : null;
            int page  = parseInt(req.get("page"), 0);
            int size  = parseInt(req.get("size"), 20);
            String sortBy  = str(req.get("sortBy"),  "createdAt");
            String sortDir = str(req.get("sortDir"), "DESC");

            var pageable = PageRequest.of(page, size,
                    Sort.by(Sort.Direction.fromString(sortDir), sortBy));
            var result = routeService.findAllWithFilters(tenantId, status, pageable);

            Map<String, Object> response = new HashMap<>();
            response.put("content",        result.getContent().stream().map(routeMapper::toSummary).toList());
            response.put("totalElements",  result.getTotalElements());
            response.put("totalPages",     result.getTotalPages());
            response.put("page",           result.getNumber());
            response.put("size",           result.getSize());
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("RabbitMQ: routes.query failed: {}", e.getMessage(), e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_ROUTES_GET)
    public String handleRouteGet(String requestBody) {
        log.debug("RabbitMQ: received routes.get request");
        try {
            Map<String, Object> req = objectMapper.readValue(requestBody, new TypeReference<>() {});
            UUID id       = parseUuid(req.get("id"));
            UUID tenantId = parseUuid(req.get("tenantId"));
            var route = routeService.findByIdWithFilters(id, tenantId);
            return objectMapper.writeValueAsString(routeMapper.toResponse(route));
        } catch (Exception e) {
            log.error("RabbitMQ: routes.get failed: {}", e.getMessage(), e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_ROUTES_CLONE)
    public String handleRouteClone(String requestBody) {
        log.debug("RabbitMQ: received routes.clone request");
        try {
            Map<String, Object> req = objectMapper.readValue(requestBody, new TypeReference<>() {});
            UUID sourceId  = parseUuid(req.get("id"));
            UUID tenantId  = parseUuid(req.get("tenantId"));
            String actor   = str(req.get("requestedBy"), "system");
            var cloned = routeService.clone(sourceId, tenantId, actor);
            return objectMapper.writeValueAsString(routeMapper.toResponse(cloned));
        } catch (Exception e) {
            log.error("RabbitMQ: routes.clone failed: {}", e.getMessage(), e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ─── Admin-API: Filter Queries ────────────────────────────────────────────

    @RabbitListener(queues = RabbitTopology.QUEUE_FILTERS_QUERY)
    public String handleFiltersQuery(String requestBody) {
        log.debug("RabbitMQ: received filters.query request");
        try {
            Map<String, Object> req = objectMapper.readValue(requestBody, new TypeReference<>() {});
            UUID tenantId  = parseUuid(req.get("tenantId"));
            int page  = parseInt(req.get("page"), 0);
            int size  = parseInt(req.get("size"), 20);
            String sortBy  = str(req.get("sortBy"),  "createdAt");
            String sortDir = str(req.get("sortDir"), "DESC");

            var pageable = PageRequest.of(page, size,
                    Sort.by(Sort.Direction.fromString(sortDir), sortBy));
            var result = filterService.findAll(tenantId, pageable);

            Map<String, Object> response = new HashMap<>();
            response.put("content",        result.getContent().stream().map(routeMapper::toFilterSummary).toList());
            response.put("totalElements",  result.getTotalElements());
            response.put("totalPages",     result.getTotalPages());
            response.put("page",           result.getNumber());
            response.put("size",           result.getSize());
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("RabbitMQ: filters.query failed: {}", e.getMessage(), e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_FILTERS_GET)
    public String handleFilterGet(String requestBody) {
        log.debug("RabbitMQ: received filters.get request");
        try {
            Map<String, Object> req = objectMapper.readValue(requestBody, new TypeReference<>() {});
            UUID id       = parseUuid(req.get("id"));
            UUID tenantId = parseUuid(req.get("tenantId"));
            var filter = filterService.findById(id, tenantId);
            return objectMapper.writeValueAsString(routeMapper.toFilterResponse(filter));
        } catch (Exception e) {
            log.error("RabbitMQ: filters.get failed: {}", e.getMessage(), e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private UUID parseUuid(Object val) {
        if (val == null || val.toString().isBlank()) return null;
        return UUID.fromString(val.toString());
    }

    private int parseInt(Object val, int def) {
        try { return val != null ? Integer.parseInt(val.toString()) : def; } catch (Exception e) { return def; }
    }

    private String str(Object val) {
        return val != null && !val.toString().isBlank() ? val.toString() : null;
    }

    private String str(Object val, String def) {
        return val != null && !val.toString().isBlank() ? val.toString() : def;
    }
}

