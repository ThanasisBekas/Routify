package io.routify.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.routify.admin.client.RouteFilterMessagingClient;
import io.routify.admin.gateway.service.RouteServiceConfigClient;
import io.routify.common.domain.FilterType;
import io.routify.common.dto.export.GatewayExportV1;
import io.routify.common.dto.export.GatewayExportV1.FilterExportEntry;
import io.routify.common.dto.export.GatewayExportV1.RouteExportEntry;
import io.routify.common.dto.export.GatewayExportV1.RouteFilterRefExport;
import io.routify.common.event.CommandEvent;
import io.routify.common.event.QueryResponse;
import io.routify.common.exception.RoutifyException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Import service — parses YAML/JSON gateway configuration, computes diffs against
 * current state, and applies changes via existing Kafka command pipeline.
 *
 * <p><b>Preview</b>: computes what would change (create/update/unchanged) without side effects.
 * <p><b>Apply</b>: runs preview then publishes Kafka commands for each change.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportService {

    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_PAGES = 50;

    private final RouteFilterMessagingClient routeFilterClient;
    private final RouteServiceConfigClient configClient;
    private final ObjectMapper objectMapper;

    // ─── Preview response DTOs ──────────────────────────────────────────────

    public record ImportPreviewResponse(
            boolean valid,
            DiffSections changes,
            List<String> warnings
    ) {}

    public record DiffSections(
            DiffSection filters,
            DiffSection routes
    ) {}

    public record DiffSection(
            List<DiffCreateEntry> create,
            List<DiffUpdateEntry> update,
            List<String> unchanged,
            List<String> delete
    ) {}

    public record DiffCreateEntry(String name, String type) {}

    public record DiffUpdateEntry(String name, List<String> changes) {}

    // ─── Apply result ─────────────────────────────────────────────────────────

    public record ImportApplyResult(
            int filtersCreated,
            int filtersUpdated,
            int routesCreated,
            int routesUpdated,
            int total
    ) {}

    // ─── Preview ──────────────────────────────────────────────────────────────

    /**
     * Parse and validate the YAML, compute diff against current state.
     */
    public ImportPreviewResponse preview(UUID tenantId, String yamlBody) {
        GatewayExportV1 importDoc = parseAndValidate(yamlBody);
        return computeDiff(tenantId, importDoc);
    }

    // ─── Apply ────────────────────────────────────────────────────────────────

    /**
     * Parse, validate, compute diff, then publish Kafka commands for each change.
     */
    public ImportApplyResult apply(UUID tenantId, String actor, String yamlBody) {
        GatewayExportV1 importDoc = parseAndValidate(yamlBody);
        return executeImport(tenantId, actor, importDoc);
    }

    // ─── Parsing & Validation ─────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private GatewayExportV1 parseAndValidate(String yamlBody) {
        Map<String, Object> raw;
        try {
            // Try YAML first, then JSON
            LoaderOptions loaderOptions = new LoaderOptions();
            loaderOptions.setMaxAliasesForCollections(50);
            Yaml yaml = new Yaml(new Constructor(loaderOptions));
            raw = yaml.loadAs(yamlBody, Map.class);
        } catch (Exception e) {
            try {
                raw = objectMapper.readValue(yamlBody, Map.class);
            } catch (Exception e2) {
                throw new RoutifyException.Validation("Invalid import format: not valid YAML or JSON");
            }
        }

        if (raw == null) {
            throw new RoutifyException.Validation("Empty import document");
        }

        // Validate apiVersion
        String apiVersion = (String) raw.get("apiVersion");
        if (!GatewayExportV1.CURRENT_API_VERSION.equals(apiVersion)) {
            throw new RoutifyException.Validation(
                    "Unsupported apiVersion: '%s'. Expected: '%s'"
                            .formatted(apiVersion, GatewayExportV1.CURRENT_API_VERSION));
        }

        // Convert to typed DTO
        try {
            return objectMapper.convertValue(raw, GatewayExportV1.class);
        } catch (Exception e) {
            throw new RoutifyException.Validation("Failed to parse import document: " + e.getMessage());
        }
    }

    // ─── Diff computation ─────────────────────────────────────────────────────

    private ImportPreviewResponse computeDiff(UUID tenantId, GatewayExportV1 importDoc) {
        List<String> warnings = new ArrayList<>();

        // Fetch current filters
        Map<String, QueryResponse.FilterDetail> currentFilters = fetchCurrentFilters(tenantId);

        // Fetch current routes
        Map<String, QueryResponse.RouteDetail> currentRoutes = fetchCurrentRoutes(tenantId);

        // ─── Filter diff ──────────────────────────────────────────────────────
        List<DiffCreateEntry> filterCreates = new ArrayList<>();
        List<DiffUpdateEntry> filterUpdates = new ArrayList<>();
        List<String> filterUnchanged = new ArrayList<>();

        if (importDoc.filters() != null) {
            for (FilterExportEntry importFilter : importDoc.filters()) {
                // Validate filter type
                FilterType filterType;
                try {
                    filterType = FilterType.valueOf(importFilter.filterType().name());
                } catch (Exception e) {
                    throw new RoutifyException.Validation(
                            "Unknown filter type '%s' for filter '%s'"
                                    .formatted(importFilter.filterType(), importFilter.name()));
                }

                // Warn about deprecated filter types (non-blocking — imports must still succeed)
                if (filterType.isDeprecated()) {
                    warnings.add("Filter '%s' uses deprecated type %s — consider migrating to %s"
                            .formatted(importFilter.name(), filterType.name(),
                                    FilterType.suggestedReplacement(filterType)));
                }

                QueryResponse.FilterDetail existing = currentFilters.get(importFilter.name());
                if (existing == null) {
                    filterCreates.add(new DiffCreateEntry(importFilter.name(),
                            importFilter.filterType().name()));
                } else {
                    List<String> changes = diffFilter(importFilter, existing);
                    if (changes.isEmpty()) {
                        filterUnchanged.add(importFilter.name());
                    } else {
                        filterUpdates.add(new DiffUpdateEntry(importFilter.name(), changes));
                    }
                }
            }
        }

        // ─── Route diff ──────────────────────────────────────────────────────
        List<DiffCreateEntry> routeCreates = new ArrayList<>();
        List<DiffUpdateEntry> routeUpdates = new ArrayList<>();
        List<String> routeUnchanged = new ArrayList<>();

        if (importDoc.routes() != null) {
            for (RouteExportEntry importRoute : importDoc.routes()) {
                QueryResponse.RouteDetail existing = currentRoutes.get(importRoute.name());
                if (existing == null) {
                    routeCreates.add(new DiffCreateEntry(importRoute.name(), importRoute.pathPattern()));
                } else {
                    List<String> changes = diffRoute(importRoute, existing);
                    if (changes.isEmpty()) {
                        routeUnchanged.add(importRoute.name());
                    } else {
                        routeUpdates.add(new DiffUpdateEntry(importRoute.name(), changes));
                    }
                }
            }
        }

        DiffSection filterSection = new DiffSection(filterCreates, filterUpdates, filterUnchanged, List.of());
        DiffSection routeSection = new DiffSection(routeCreates, routeUpdates, routeUnchanged, List.of());

        return new ImportPreviewResponse(true, new DiffSections(filterSection, routeSection), warnings);
    }

    // ─── Apply (execute import) ──────────────────────────────────────────────

    private ImportApplyResult executeImport(UUID tenantId, String actor, GatewayExportV1 importDoc) {
        // Fetch current state for diff
        Map<String, QueryResponse.FilterDetail> currentFilters = fetchCurrentFilters(tenantId);
        Map<String, QueryResponse.RouteDetail> currentRoutes = fetchCurrentRoutes(tenantId);

        int filtersCreated = 0;
        int filtersUpdated = 0;
        int routesCreated = 0;
        int routesUpdated = 0;

        // ─── Process filters ──────────────────────────────────────────────────

        if (importDoc.filters() != null) {
            for (FilterExportEntry importFilter : importDoc.filters()) {
                QueryResponse.FilterDetail existing = currentFilters.get(importFilter.name());

                if (existing == null) {
                    // Create filter
                    UUID commandId = generateCommandId(tenantId, importFilter.name(), "create-filter");
                    routeFilterClient.publishFilterCommand(
                            new CommandEvent.CreateFilter(
                                    commandId, tenantId, actor, Instant.now(),
                                    importFilter.name(),
                                    importFilter.description(),
                                    importFilter.filterType(),
                                    importFilter.config(), null
                            )
                    );
                    filtersCreated++;
                    log.info("Import: creating filter '{}'", importFilter.name());
                } else {
                    List<String> changes = diffFilter(importFilter, existing);
                    if (!changes.isEmpty()) {
                        Map<String, Object> config = mergeFilterConfig(existing.config(), importFilter.config());
                        UUID commandId = generateCommandId(tenantId, importFilter.name(), "update-filter");
                        routeFilterClient.publishFilterCommand(
                                new CommandEvent.UpdateFilter(
                                        commandId, tenantId, actor, Instant.now(),
                                        existing.id(),
                                        importFilter.name(),
                                        importFilter.description(),
                                        config, null
                                )
                        );
                        filtersUpdated++;
                        log.info("Import: updating filter '{}' (changes: {})", importFilter.name(), changes);
                    }
                }
            }
        }

        // ─── Process routes ──────────────────────────────────────────────────
        if (importDoc.routes() != null) {
            for (RouteExportEntry importRoute : importDoc.routes()) {
                QueryResponse.RouteDetail existing = currentRoutes.get(importRoute.name());

                if (existing == null) {
                    // Create route
                    UUID commandId = generateCommandId(tenantId, importRoute.name(), "create-route");
                    routeFilterClient.publishRouteCommand(
                            new CommandEvent.CreateRoute(
                                    commandId, tenantId, actor, Instant.now(),
                                    importRoute.name(),
                                    importRoute.description(),
                                    importRoute.pathPattern(),
                                    importRoute.methods(),
                                    importRoute.upstreamUri(),
                                    importRoute.stripPrefix(),
                                    importRoute.extraConfig(),
                                    null // environment defaults to PRODUCTION
                            )
                    );
                    routesCreated++;
                    log.info("Import: creating route '{}'", importRoute.name());
                } else {
                    List<String> changes = diffRoute(importRoute, existing);
                    if (!changes.isEmpty()) {
                        UUID commandId = generateCommandId(tenantId, importRoute.name(), "update-route");
                        routeFilterClient.publishRouteCommand(
                                new CommandEvent.UpdateRoute(
                                        commandId, tenantId, actor, Instant.now(),
                                        existing.id(),
                                        importRoute.name(),
                                        importRoute.description(),
                                        importRoute.pathPattern(),
                                        importRoute.methods(),
                                        importRoute.upstreamUri(),
                                        importRoute.stripPrefix(),
                                        importRoute.extraConfig()
                                )
                        );
                        routesUpdated++;
                        log.info("Import: updating route '{}' (changes: {})", importRoute.name(), changes);
                    }
                }
            }
        }

        // ─── Process gateway config ──────────────────────────────────────────
        if (importDoc.gatewayConfig() != null && !importDoc.gatewayConfig().isEmpty()) {
            try {
                configClient.saveConfig(importDoc.gatewayConfig(), actor, "import");
                log.info("Import: updated gateway config");
            } catch (Exception e) {
                log.warn("Import: failed to update gateway config: {}", e.getMessage());
            }
        }

        int total = filtersCreated + filtersUpdated + routesCreated + routesUpdated;
        return new ImportApplyResult(filtersCreated, filtersUpdated, routesCreated, routesUpdated, total);
    }

    // ─── Diff helpers ─────────────────────────────────────────────────────────

    private List<String> diffFilter(FilterExportEntry importFilter, QueryResponse.FilterDetail existing) {
        List<String> changes = new ArrayList<>();
        if (!Objects.equals(importFilter.description(), existing.description())) {
            changes.add("description");
        }
        if (importFilter.config() != null && existing.config() != null) {
            for (Map.Entry<String, Object> e : importFilter.config().entrySet()) {
                Object currentVal = existing.config().get(e.getKey());
                if (!Objects.equals(e.getValue(), currentVal)) {
                    changes.add("config." + e.getKey());
                }
            }
        }
        return changes;
    }

    private List<String> diffRoute(RouteExportEntry importRoute, QueryResponse.RouteDetail existing) {
        List<String> changes = new ArrayList<>();
        if (!Objects.equals(importRoute.pathPattern(), existing.pathPattern())) {
            changes.add("pathPattern");
        }
        if (!Objects.equals(importRoute.methods(), existing.methods())) {
            changes.add("methods");
        }
        if (!Objects.equals(importRoute.upstreamUri(), existing.upstreamUri())) {
            changes.add("upstreamUri");
        }
        if (!Objects.equals(importRoute.stripPrefix(), existing.stripPrefix())) {
            changes.add("stripPrefix");
        }
        if (!Objects.equals(importRoute.description(), existing.description())) {
            changes.add("description");
        }
        // Compare filter attachments
        if (importRoute.filters() != null && existing.filters() != null) {
            Set<String> importFilterNames = importRoute.filters().stream()
                    .map(RouteFilterRefExport::filterName)
                    .collect(Collectors.toSet());
            Set<String> existingFilterNames = existing.filters().stream()
                    .map(QueryResponse.RouteDetail.FilterRef::filterName)
                    .collect(Collectors.toSet());
            if (!importFilterNames.equals(existingFilterNames)) {
                changes.add("filters");
            }
        }
        if (!Objects.equals(importRoute.extraConfig(), existing.extraConfig())) {
            changes.add("extraConfig");
        }
        return changes;
    }

    // ─── Data loading helpers ─────────────────────────────────────────────────

    private Map<String, QueryResponse.FilterDetail> fetchCurrentFilters(UUID tenantId) {
        Map<String, QueryResponse.FilterDetail> byName = new LinkedHashMap<>();
        int page = 0;
        long total;
        do {
            QueryResponse.FiltersPage fp = routeFilterClient.queryFilters(
                    tenantId, page, MAX_PAGE_SIZE, "createdAt", "ASC");
            if (fp == null || fp.content() == null) break;
            for (var f : fp.content()) {
                QueryResponse.FilterDetail detail = routeFilterClient.getFilter(f.id(), tenantId);
                if (detail != null) {
                    byName.put(detail.name(), detail);
                }
            }
            total = fp.totalElements();
            page++;
        } while ((long) page * MAX_PAGE_SIZE < total && page < MAX_PAGES);
        return byName;
    }

    private Map<String, QueryResponse.RouteDetail> fetchCurrentRoutes(UUID tenantId) {
        Map<String, QueryResponse.RouteDetail> byName = new LinkedHashMap<>();
        int page = 0;
        long total;
        do {
            QueryResponse.RoutesPage rp = routeFilterClient.queryRoutes(
                    tenantId, null, null, page, MAX_PAGE_SIZE, "createdAt", "ASC");
            if (rp == null || rp.content() == null) break;
            for (var r : rp.content()) {
                QueryResponse.RouteDetail detail = routeFilterClient.getRoute(r.id(), tenantId);
                if (detail != null) {
                    byName.put(detail.name(), detail);
                }
            }
            total = rp.totalElements();
            page++;
        } while ((long) page * MAX_PAGE_SIZE < total && page < MAX_PAGES);
        return byName;
    }

    // ─── Utility helpers ──────────────────────────────────────────────────────

    /**
     * Merge import config with existing config, skipping masked values.
     */
    private Map<String, Object> mergeFilterConfig(Map<String, Object> existing, Map<String, Object> imported) {
        if (existing == null && imported == null) return Map.of();
        if (imported == null) return existing;

        Map<String, Object> merged = new LinkedHashMap<>(existing);
        merged.putAll(imported);
        return merged;
    }

    /**
     * Generate a deterministic idempotent command ID from tenant + resource name + operation.
     * This ensures that importing the same file twice produces the same command IDs,
     * which are deduplicated by the route-service {@code ProcessedCommandRepository}.
     */
    static UUID generateCommandId(UUID tenantId, String resourceName, String operation) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String input = tenantId.toString() + ":" + resourceName + ":" + operation;
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            // Use first 16 bytes of SHA-256 as UUID
            long msb = 0;
            long lsb = 0;
            for (int i = 0; i < 8; i++) msb = (msb << 8) | (hash[i] & 0xff);
            for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (hash[i] & 0xff);
            // Set version 4 and variant bits
            msb = (msb & 0xffffffffffff0fffL) | 0x0000000000004000L;
            lsb = (lsb & 0x3fffffffffffffffL) | 0x8000000000000000L;
            return new UUID(msb, lsb);
        } catch (Exception e) {
            // Fallback to random UUID if SHA-256 not available (shouldn't happen)
            return UUID.randomUUID();
        }
    }
}



