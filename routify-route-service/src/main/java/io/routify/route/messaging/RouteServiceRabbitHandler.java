package io.routify.route.messaging;

import io.routify.common.domain.RouteStatus;
import io.routify.common.event.QueryRequest;
import io.routify.common.event.QueryResponse;
import io.routify.common.event.RabbitTopology;
import io.routify.route.mapper.RouteMapper;
import io.routify.route.service.FilterDefinitionService;
import io.routify.route.service.GatewayConfigService;
import io.routify.route.service.RouteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import io.routify.route.dto.RouteStatusCount;


/**
 * RabbitMQ request/reply handler for routify-route-service.
 *
 * <p>All request bodies are deserialised into strongly-typed {@link QueryRequest} records
 * by the Jackson2JsonMessageConverter in the listener container.
 * Return values are serialised back to JSON automatically by the same converter.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouteServiceRabbitHandler {

    private final RouteService             routeService;
    private final FilterDefinitionService  filterService;
    private final GatewayConfigService     gatewayConfigService;
    private final RouteMapper              routeMapper;

    @RabbitListener(queues = RabbitTopology.QUEUE_ROUTE_GATEWAY_SNAPSHOT)
    public QueryResponse.GatewaySnapshotList handleGatewaySnapshotRequest(@SuppressWarnings("unused") QueryRequest.GatewaySnapshot request) {
        log.debug("RabbitMQ: received gateway snapshot request");
        var snapshots = routeService.findAllActiveWithFilters()
                .stream()
                .map(route -> {
                    var dto = routeMapper.toGatewaySnapshot(route);
                    var filters = dto.filters().stream()
                            .map(f -> new QueryResponse.GatewaySnapshotList.RouteSnapshot.FilterSnapshot(
                                    f.filterId(), f.filterType(), f.order(), f.phase(),
                                    f.config(), f.gatewayConfigRef()))
                            .toList();
                    return new QueryResponse.GatewaySnapshotList.RouteSnapshot(
                            dto.routeId(), dto.tenantId(), dto.name(), dto.pathPattern(),
                            dto.methods(), dto.upstreamUri(), dto.stripPrefix(), dto.version(),
                            filters, dto.extraConfig());
                })
                .toList();
        log.debug("RabbitMQ: returning {} active routes in snapshot", snapshots.size());
        return new QueryResponse.GatewaySnapshotList(snapshots);
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_GATEWAY_CONFIG_GET)
    public QueryResponse.GatewayConfig handleGatewayConfigGet(@SuppressWarnings("unused") QueryRequest.GatewayConfigGet request) {
        log.debug("RabbitMQ: received gateway config GET request");
        return new QueryResponse.GatewayConfig(gatewayConfigService.getGlobalConfig());
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_GATEWAY_CONFIG_SAVE)
    public QueryResponse.GatewayConfig handleGatewayConfigSave(QueryRequest.GatewayConfigSave req) {
        log.debug("RabbitMQ: received gateway config SAVE request");
        String section   = req.section()   != null ? req.section()   : "full";
        String changedBy = req.changedBy() != null ? req.changedBy() : "system";
        var saved = gatewayConfigService.saveGlobalConfig(req.config(), changedBy, section);
        log.info("RabbitMQ: gateway config SAVE complete: section={} by={}", section, changedBy);
        return new QueryResponse.GatewayConfig(saved);
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_ROUTE_STATS)
    public QueryResponse.RouteStatsResult handleRouteStats(QueryRequest.RouteStats req) {
        log.debug("RabbitMQ: received route stats request");
        long total = 0L;
        long active = 0L, inactive = 0L, draft = 0L;
        if (req.tenantId() != null) {
            total = routeService.findAll(req.tenantId(), null, PageRequest.of(0, 1)).getTotalElements();
        }
        for (RouteStatusCount row : routeService.countByStatus(req.tenantId())) {
            switch (row.status()) {
                case ACTIVE   -> active   = row.count();
                case DISABLED -> inactive = row.count();
                case DRAFT    -> draft    = row.count();
            }
        }
        return new QueryResponse.RouteStatsResult(total, active, inactive, draft, req.tenantId());
    }

    // ─── Admin-API: Route Queries ─────────────────────────────────────────────

    @RabbitListener(queues = RabbitTopology.QUEUE_ROUTES_QUERY)
    public QueryResponse.RoutesPage handleRoutesQuery(QueryRequest.RoutesQuery req) {
        log.debug("RabbitMQ: received routes.query request");
        RouteStatus status = (req.status() != null && !req.status().isBlank())
                ? RouteStatus.valueOf(req.status().toUpperCase()) : null;
        String sortBy  = req.sortBy()  != null ? req.sortBy()  : "createdAt";
        String sortDir = req.sortDir() != null ? req.sortDir() : "DESC";

        var pageable = PageRequest.of(req.page(), req.size(),
                Sort.by(Sort.Direction.fromString(sortDir), sortBy));
        var result = routeService.findAllWithFilters(req.tenantId(), status, pageable);

        var content = result.getContent().stream().map(routeMapper::toSummary).map(s ->
                new QueryResponse.RoutesPage.RouteSummary(
                        s.id(), s.name(), s.description(), s.pathPattern(), s.methods(),
                        s.upstreamUri(), s.status(), s.version(), s.filterCount(),
                        s.createdAt(), s.activatedAt()))
                .toList();
        return new QueryResponse.RoutesPage(content, result.getTotalElements(),
                result.getTotalPages(), result.getNumber(), result.getSize());
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_ROUTES_GET)
    public QueryResponse.RouteDetail handleRouteGet(QueryRequest.RouteGet req) {
        log.debug("RabbitMQ: received routes.get request");
        var r = routeMapper.toResponse(routeService.findByIdWithFilters(req.id(), req.tenantId()));
        var filters = r.filters().stream().map(f ->
                new QueryResponse.RouteDetail.FilterRef(
                        f.filterId(), f.filterName(), f.filterType(),
                        f.order(), f.phase(), f.enabled()))
                .toList();
        return new QueryResponse.RouteDetail(
                r.id(), r.tenantId(), r.name(), r.description(), r.pathPattern(),
                r.methods(), r.upstreamUri(), r.stripPrefix(), r.status(), r.version(),
                filters, r.extraConfig(), r.createdBy(), r.createdAt(), r.updatedAt(), r.activatedAt());
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_ROUTES_CLONE)
    public QueryResponse.RouteDetail handleRouteClone(QueryRequest.RouteClone req) {
        log.debug("RabbitMQ: received routes.clone request");
        String actor = req.requestedBy() != null ? req.requestedBy() : "system";
        var r = routeMapper.toResponse(routeService.clone(req.id(), req.tenantId(), actor));
        var filters = r.filters().stream().map(f ->
                new QueryResponse.RouteDetail.FilterRef(
                        f.filterId(), f.filterName(), f.filterType(),
                        f.order(), f.phase(), f.enabled()))
                .toList();
        return new QueryResponse.RouteDetail(
                r.id(), r.tenantId(), r.name(), r.description(), r.pathPattern(),
                r.methods(), r.upstreamUri(), r.stripPrefix(), r.status(), r.version(),
                filters, r.extraConfig(), r.createdBy(), r.createdAt(), r.updatedAt(), r.activatedAt());
    }

    // ─── Admin-API: Filter Queries ────────────────────────────────────────────

    @RabbitListener(queues = RabbitTopology.QUEUE_FILTERS_QUERY)
    public QueryResponse.FiltersPage handleFiltersQuery(QueryRequest.FiltersQuery req) {
        log.debug("RabbitMQ: received filters.query request");
        String sortBy  = req.sortBy()  != null ? req.sortBy()  : "createdAt";
        String sortDir = req.sortDir() != null ? req.sortDir() : "DESC";

        var pageable = PageRequest.of(req.page(), req.size(),
                Sort.by(Sort.Direction.fromString(sortDir), sortBy));
        var result = filterService.findAll(req.tenantId(), pageable);

        var content = result.getContent().stream().map(routeMapper::toFilterSummary).map(s -> {
            QueryResponse.GatewayConfigRef gcr = s.gatewayConfigRef() != null
                    ? new QueryResponse.GatewayConfigRef(
                            s.gatewayConfigRef().refType(),
                            s.gatewayConfigRef().refId(),
                            s.gatewayConfigRef().refName())
                    : null;
            return new QueryResponse.FiltersPage.FilterSummary(
                    s.id(), s.name(), s.filterType(), s.enabled(),
                    s.usageCount(), gcr, s.createdAt());
        }).toList();
        return new QueryResponse.FiltersPage(content, result.getTotalElements(),
                result.getTotalPages(), result.getNumber(), result.getSize());
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_FILTERS_GET)
    public QueryResponse.FilterDetail handleFilterGet(QueryRequest.FilterGet req) {
        log.debug("RabbitMQ: received filters.get request");
        var f = routeMapper.toFilterResponse(filterService.findById(req.id(), req.tenantId()));
        QueryResponse.GatewayConfigRef gcr = f.gatewayConfigRef() != null
                ? new QueryResponse.GatewayConfigRef(
                        f.gatewayConfigRef().refType(),
                        f.gatewayConfigRef().refId(),
                        f.gatewayConfigRef().refName())
                : null;
        return new QueryResponse.FilterDetail(
                f.id(), f.tenantId(), f.name(), f.description(), f.filterType(),
                f.config(), f.systemManaged(), f.enabled(), f.usageCount(),
                gcr, f.createdBy(), f.createdAt(), f.updatedAt());
    }
}
