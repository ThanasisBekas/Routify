package io.routify.route.mapper;

import io.routify.route.domain.FilterDefinition;
import io.routify.route.domain.Route;
import io.routify.route.domain.RouteFilter;
import io.routify.route.dto.FilterDefinitionDto;
import io.routify.route.dto.RouteDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;
import java.util.Map;

/**
 * MapStruct mapper for Route and FilterDefinition domain↔DTO conversions.
 */
@Mapper(componentModel = "spring")
public interface RouteMapper {

    // ─── Route ────────────────────────────────────────────────────────────────

    @Mapping(target = "filters", source = "filters", qualifiedByName = "toFilterRefs")
    RouteDto.Response toResponse(Route route);

    @Mapping(target = "filterCount", expression = "java(route.getFilters().size())")
    RouteDto.Summary toSummary(Route route);

    Route fromCreateRequest(RouteDto.CreateRequest req);

    @Named("toFilterRefs")
    default List<RouteDto.FilterRef> toFilterRefs(List<RouteFilter> filters) {
        if (filters == null) return List.of();
        return filters.stream()
                .map(f -> new RouteDto.FilterRef(
                        f.getFilterDefinition().getId(),
                        f.getFilterDefinition().getName(),
                        f.getFilterDefinition().getFilterType().name(),
                        f.getFilterOrder(),
                        f.getPhase(),
                        f.isEnabled()))
                .toList();
    }

    default RouteDto.GatewaySnapshot toGatewaySnapshot(Route route) {
        var filterSnapshots = route.getFilters().stream()
                .filter(RouteFilter::isEnabled)
                .map(f -> new RouteDto.GatewaySnapshot.FilterSnapshot(
                        f.getFilterDefinition().getId(),
                        f.getFilterDefinition().getFilterType().name(),
                        f.getFilterOrder(),
                        f.getPhase(),
                        f.getFilterDefinition().getConfig(),
                        f.getFilterDefinition().getGatewayConfigRef()))
                .toList();

        return new RouteDto.GatewaySnapshot(
                route.getId(),
                route.getTenantId(),
                route.getName(),
                route.getPathPattern(),
                route.getMethods(),
                route.getUpstreamUri(),
                route.getStripPrefix(),
                route.getVersion(),
                route.getEnvironment() != null ? route.getEnvironment().name() : "PRODUCTION",
                filterSnapshots,
                route.getExtraConfig());
    }

    // ─── FilterDefinition ─────────────────────────────────────────────────────

    @Mapping(target = "gatewayConfigRef", source = "gatewayConfigRef", qualifiedByName = "toGatewayConfigRef")
    FilterDefinitionDto.Response toFilterResponse(FilterDefinition filter);

    @Mapping(target = "gatewayConfigRef", source = "gatewayConfigRef", qualifiedByName = "toGatewayConfigRef")
    FilterDefinitionDto.Summary toFilterSummary(FilterDefinition filter);

    @Mapping(target = "gatewayConfigRef", ignore = true)
    FilterDefinition fromFilterCreateRequest(FilterDefinitionDto.CreateRequest req);

    @Named("toGatewayConfigRef")
    default FilterDefinitionDto.GatewayConfigRef toGatewayConfigRef(Map<String, Object> ref) {
        if (ref == null) return null;
        Object refType = ref.get("refType");
        Object refId   = ref.get("refId");
        Object refName = ref.get("refName");
        if (refType == null || refId == null) return null;
        return new FilterDefinitionDto.GatewayConfigRef(
                refType.toString(),
                refId.toString(),
                refName != null ? refName.toString() : null);
    }
}
