package gr.routify.route.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.common.domain.RouteStatus;
import gr.routify.common.event.QueryRequest;
import gr.routify.common.event.RabbitTopology;
import gr.routify.route.mapper.RouteMapper;
import gr.routify.route.service.FilterDefinitionService;
import gr.routify.route.service.GatewayConfigService;
import gr.routify.route.service.RouteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import gr.routify.route.dto.RouteStatusCount;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * RabbitMQ request/reply handler for routify-route-service.
 *
 * <p>All request bodies are deserialised into strongly-typed {@link QueryRequest} records.
 * The {@code "type"} discriminator embedded by Jackson makes the wire format self-describing.
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
    public String handleGatewayConfigSave(String requestBody) {
        log.debug("RabbitMQ: received gateway config SAVE request");
        try {
            QueryRequest.GatewayConfigSave req = objectMapper.readValue(
                    requestBody, QueryRequest.GatewayConfigSave.class);
            String section    = req.section()    != null ? req.section()    : "full";
            String changedBy  = req.changedBy()  != null ? req.changedBy()  : "system";
            Map<String, Object> saved = gatewayConfigService.saveGlobalConfig(
                    req.config(), changedBy, section);
            log.info("RabbitMQ: gateway config SAVE complete: section={} by={}", section, changedBy);
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
            QueryRequest.RouteStats req = objectMapper.readValue(
                    requestBody, QueryRequest.RouteStats.class);
            UUID tenantId = req.tenantId();

            Map<String, Object> stats = new HashMap<>();
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
            QueryRequest.RoutesQuery req = objectMapper.readValue(
                    requestBody, QueryRequest.RoutesQuery.class);
            RouteStatus status = (req.status() != null && !req.status().isBlank())
                    ? RouteStatus.valueOf(req.status().toUpperCase()) : null;
            String sortBy  = req.sortBy()  != null ? req.sortBy()  : "createdAt";
            String sortDir = req.sortDir() != null ? req.sortDir() : "DESC";

            var pageable = PageRequest.of(req.page(), req.size(),
                    Sort.by(Sort.Direction.fromString(sortDir), sortBy));
            var result = routeService.findAllWithFilters(req.tenantId(), status, pageable);

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
            QueryRequest.RouteGet req = objectMapper.readValue(
                    requestBody, QueryRequest.RouteGet.class);
            var route = routeService.findByIdWithFilters(req.id(), req.tenantId());
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
            QueryRequest.RouteClone req = objectMapper.readValue(
                    requestBody, QueryRequest.RouteClone.class);
            String actor = req.requestedBy() != null ? req.requestedBy() : "system";
            var cloned = routeService.clone(req.id(), req.tenantId(), actor);
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
            QueryRequest.FiltersQuery req = objectMapper.readValue(
                    requestBody, QueryRequest.FiltersQuery.class);
            String sortBy  = req.sortBy()  != null ? req.sortBy()  : "createdAt";
            String sortDir = req.sortDir() != null ? req.sortDir() : "DESC";

            var pageable = PageRequest.of(req.page(), req.size(),
                    Sort.by(Sort.Direction.fromString(sortDir), sortBy));
            var result = filterService.findAll(req.tenantId(), pageable);

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
            QueryRequest.FilterGet req = objectMapper.readValue(
                    requestBody, QueryRequest.FilterGet.class);
            var filter = filterService.findById(req.id(), req.tenantId());
            return objectMapper.writeValueAsString(routeMapper.toFilterResponse(filter));
        } catch (Exception e) {
            log.error("RabbitMQ: filters.get failed: {}", e.getMessage(), e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}
