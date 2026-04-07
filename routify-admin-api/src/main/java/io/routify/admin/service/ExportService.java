package io.routify.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.routify.admin.client.RouteFilterMessagingClient;
import io.routify.admin.gateway.service.RouteServiceConfigClient;
import io.routify.common.dto.export.GatewayExportV1;
import io.routify.common.dto.export.GatewayExportV1.ExportMetadata;
import io.routify.common.dto.export.GatewayExportV1.FilterExportEntry;
import io.routify.common.dto.export.GatewayExportV1.RouteExportEntry;
import io.routify.common.dto.export.GatewayExportV1.RouteFilterRefExport;
import io.routify.common.event.QueryResponse;
import io.routify.common.web.Sensitive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.time.Instant;
import java.util.*;

/**
 * Assembles a complete {@link GatewayExportV1} document from route-service data
 * and serialises it to YAML or JSON.
 *
 * <p>All data is fetched via RabbitMQ queries through the existing messaging clients.
 * Sensitive filter config values are masked before export.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportService {

    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_PAGES = 50;

    private final RouteFilterMessagingClient routeFilterClient;
    private final RouteServiceConfigClient configClient;
    private final ObjectMapper objectMapper;

    /**
     * Export the full gateway configuration for the given tenant.
     *
     * @param tenantId    tenant to export
     * @param environment optional environment filter (PRODUCTION, STAGING, or null for all)
     * @param actor       user who triggered the export
     * @return fully assembled export DTO with sensitive fields masked
     */
    public GatewayExportV1 buildExport(UUID tenantId, String environment, String actor) {
        List<FilterExportEntry> filterEntries = fetchAllFilters(tenantId);
        List<RouteExportEntry> routeEntries = fetchAllRoutes(tenantId, environment);
        Map<String, Object> gatewayConfig = fetchGatewayConfig();

        ExportMetadata metadata = new ExportMetadata(
                Instant.now(),
                actor,
                tenantId,
                environment
        );

        return new GatewayExportV1(
                GatewayExportV1.CURRENT_API_VERSION,
                GatewayExportV1.KIND,
                metadata,
                filterEntries,
                routeEntries,
                gatewayConfig.isEmpty() ? null : gatewayConfig
        );
    }

    /**
     * Serialise the export DTO to a YAML string with block-style formatting.
     */
    @SuppressWarnings("unchecked")
    public String toYaml(GatewayExportV1 export) {
        // Convert to a Map via Jackson to get clean YAML output
        Map<String, Object> map = objectMapper.convertValue(export, Map.class);
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        options.setIndicatorIndent(0);
        options.setWidth(120);
        Yaml yaml = new Yaml(options);
        return yaml.dump(map);
    }

    /**
     * Serialise the export DTO to a JSON string.
     */
    public String toJson(GatewayExportV1 export) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(export);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise export to JSON", e);
        }
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private List<FilterExportEntry> fetchAllFilters(UUID tenantId) {
        List<FilterExportEntry> all = new ArrayList<>();
        int page = 0;
        long totalElements;
        do {
            QueryResponse.FiltersPage filtersPage = routeFilterClient.queryFilters(
                    tenantId, page, MAX_PAGE_SIZE, "createdAt", "ASC");
            if (filtersPage == null || filtersPage.content() == null) break;
            for (var f : filtersPage.content()) {
                all.add(mapFilter(f, tenantId));
            }
            totalElements = filtersPage.totalElements();
            page++;
        } while ((long) page * MAX_PAGE_SIZE < totalElements && page < MAX_PAGES);

        return all;
    }

    private FilterExportEntry mapFilter(QueryResponse.FiltersPage.FilterSummary summary, UUID tenantId) {
        // Fetch full detail to get the config map
        QueryResponse.FilterDetail detail = routeFilterClient.getFilter(summary.id(), tenantId);
        Map<String, Object> config = detail != null && detail.config() != null
                ? maskSensitiveConfig(new LinkedHashMap<>(detail.config()))
                : Map.of();
        return new FilterExportEntry(
                summary.name(),
                summary.filterType(),
                detail != null ? detail.description() : null,
                summary.enabled() != null && summary.enabled(),
                config
        );
    }

    private List<RouteExportEntry> fetchAllRoutes(UUID tenantId, String environment) {
        List<RouteExportEntry> all = new ArrayList<>();
        int page = 0;
        long totalElements;
        do {
            QueryResponse.RoutesPage routesPage = routeFilterClient.queryRoutes(
                    tenantId, null, environment, page, MAX_PAGE_SIZE, "createdAt", "ASC");
            if (routesPage == null || routesPage.content() == null) break;
            for (var r : routesPage.content()) {
                all.add(mapRoute(r, tenantId));
            }
            totalElements = routesPage.totalElements();
            page++;
        } while ((long) page * MAX_PAGE_SIZE < totalElements && page < MAX_PAGES);

        return all;
    }

    private RouteExportEntry mapRoute(QueryResponse.RoutesPage.RouteSummary summary, UUID tenantId) {
        // Fetch full detail to get filters and extra config
        QueryResponse.RouteDetail detail = routeFilterClient.getRoute(summary.id(), tenantId);
        List<RouteFilterRefExport> filterRefs = List.of();
        Map<String, Object> extraConfig = null;
        String stripPrefix = null;

        if (detail != null) {
            if (detail.filters() != null) {
                filterRefs = detail.filters().stream()
                        .map(f -> new RouteFilterRefExport(f.filterName(), f.order(), f.phase(), f.enabled()))
                        .toList();
            }
            extraConfig = detail.extraConfig();
            stripPrefix = detail.stripPrefix();
        }

        return new RouteExportEntry(
                summary.name(),
                summary.pathPattern(),
                summary.methods(),
                summary.upstreamUri(),
                stripPrefix,
                summary.status(),
                summary.description(),
                filterRefs,
                extraConfig
        );
    }

    private Map<String, Object> fetchGatewayConfig() {
        try {
            return configClient.fetchConfig();
        } catch (Exception e) {
            log.warn("Failed to fetch gateway config for export: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * Mask values of known sensitive keys in filter config maps.
     * This covers common patterns like password, secret, privateKey, apiKey, etc.
     */
    private Map<String, Object> maskSensitiveConfig(Map<String, Object> config) {
        Set<String> sensitiveKeyPatterns = Set.of(
                "secret", "password", "privatekey", "apikey", "token",
                "clientsecret", "publickey", "key", "credential"
        );
        Map<String, Object> masked = new LinkedHashMap<>(config);
        for (Map.Entry<String, Object> entry : masked.entrySet()) {
            String keyLower = entry.getKey().toLowerCase().replaceAll("[_\\-.]", "");
            if (sensitiveKeyPatterns.contains(keyLower) && entry.getValue() instanceof String s && !s.isBlank()) {
                masked.put(entry.getKey(), Sensitive.MASK);
            }
        }
        return masked;
    }
}

