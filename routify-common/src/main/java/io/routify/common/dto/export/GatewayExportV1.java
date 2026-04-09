package io.routify.common.dto.export;

import io.routify.common.domain.FilterType;
import io.routify.common.domain.RouteStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Root DTO for the {@code routify/v1} gateway configuration export format.
 *
 * <p>Produced by {@code GET /api/v1/admin/routes/export} and consumed by
 * {@code POST /api/v1/admin/routes/import}. Serialised to/from YAML using SnakeYAML.
 *
 * <h2>Design decisions</h2>
 * <ul>
 *   <li>Filters and routes are keyed by <b>name</b> (natural key), not UUID.</li>
 *   <li>Routes reference filters by filter name, not ID.</li>
 *   <li>Sensitive config values are masked on export ({@code ***MASKED***}).</li>
 *   <li>Import skips masked values (does not overwrite stored secrets).</li>
 *   <li>{@code apiVersion} enables future schema evolution.</li>
 * </ul>
 *
 * @param apiVersion always {@code "routify/v1"}
 * @param kind       always {@code "GatewayConfiguration"}
 * @param metadata   export context (timestamp, actor, tenant, environment)
 * @param filters    all filter definitions in the tenant
 * @param routes     all route definitions in the tenant
 * @param gatewayConfig optional gateway-wide configuration (CORS, security headers, etc.)
 */
public record GatewayExportV1(
        String apiVersion,
        String kind,
        ExportMetadata metadata,
        List<FilterExportEntry> filters,
        List<RouteExportEntry> routes,
        Map<String, Object> gatewayConfig
) {

    public static final String CURRENT_API_VERSION = "routify/v1";
    public static final String KIND = "GatewayConfiguration";

    /** Export context metadata. */
    public record ExportMetadata(
            Instant exportedAt,
            String exportedBy,
            UUID tenantId,
            String environment
    ) {}

    /** A single filter definition in the export. */
    public record FilterExportEntry(
            String name,
            FilterType filterType,
            String description,
            boolean enabled,
            Map<String, Object> config,
            boolean deprecated
    ) {
        /** Backward-compatible constructor without deprecated flag. */
        public FilterExportEntry(String name, FilterType filterType, String description,
                                  boolean enabled, Map<String, Object> config) {
            this(name, filterType, description, enabled, config, false);
        }
    }

    /** A single route definition in the export. */
    public record RouteExportEntry(
            String name,
            String pathPattern,
            String methods,
            String upstreamUri,
            String stripPrefix,
            RouteStatus status,
            String description,
            List<RouteFilterRefExport> filters,
            Map<String, Object> extraConfig
    ) {}

    /** Reference to an attached filter within a route export entry. */
    public record RouteFilterRefExport(
            String filterName,
            int order,
            String phase,
            boolean enabled
    ) {}
}

