package io.routify.gateway.filter.routing;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.*;

/**
 * Gateway filter factory that routes requests to geographically closest upstream
 * endpoints using MaxMind GeoIP2 database lookups.
 *
 * <p>Enables multi-region deployments where the gateway selects the nearest
 * backend cluster based on the caller's IP geolocation.
 *
 * <h3>Resolution chain:</h3>
 * <ol>
 *   <li>Resolve client IP from {@code X-Forwarded-For} header (if present) or TCP connection</li>
 *   <li>Look up country code via MaxMind GeoIP2 database (cached in LRU Caffeine cache)</li>
 *   <li>Map country code to region code using the {@code countryToRegion} map, or use the
 *       first two letters of the country code as the region key</li>
 *   <li>Look up the region's upstream URI from the {@code regions} map</li>
 *   <li>Rewrite the route's upstream URI to the resolved region endpoint</li>
 *   <li>Inject {@code X-Geo-Region} header for downstream observability</li>
 * </ol>
 *
 * <h3>Fallback chain:</h3>
 * <ul>
 *   <li>GeoIP lookup fails → use {@code defaultRegion}</li>
 *   <li>{@code defaultRegion} not in {@code regions} map → pass through to route's original upstream</li>
 * </ul>
 *
 * <h3>Config params:</h3>
 * <table>
 *   <tr><th>Param</th><th>Type</th><th>Default</th><th>Description</th></tr>
 *   <tr><td>regions</td><td>String</td><td>""</td>
 *       <td>Comma-separated region→URI pairs (e.g. "US=https://us.api.example.com,EU=https://eu.api.example.com")</td></tr>
 *   <tr><td>defaultRegion</td><td>String</td><td>"US"</td><td>Fallback region key</td></tr>
 *   <tr><td>geoDbPath</td><td>String</td><td>"classpath:GeoLite2-Country.mmdb"</td><td>MaxMind database path</td></tr>
 *   <tr><td>cacheSize</td><td>int</td><td>10000</td><td>LRU cache size for IP → country lookups</td></tr>
 * </table>
 *
 * <p>Filter type: {@code GEO_ROUTE}
 *
 * @see GeoIpResolver
 */
@Slf4j
@Component
public class GeoRouteGatewayFilterFactory
        extends AbstractGatewayFilterFactory<GeoRouteGatewayFilterFactory.Config> {

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";
    private static final String X_GEO_REGION = "X-Geo-Region";

    /**
     * Default mapping of ISO 3166-1 alpha-2 country codes to region keys.
     * Countries not in this map fall through to defaultRegion.
     */
    private static final Map<String, String> DEFAULT_COUNTRY_TO_REGION = Map.ofEntries(
            // North America
            Map.entry("US", "US"), Map.entry("CA", "US"), Map.entry("MX", "US"),
            // Europe
            Map.entry("GB", "EU"), Map.entry("DE", "EU"), Map.entry("FR", "EU"),
            Map.entry("ES", "EU"), Map.entry("IT", "EU"), Map.entry("NL", "EU"),
            Map.entry("BE", "EU"), Map.entry("AT", "EU"), Map.entry("CH", "EU"),
            Map.entry("SE", "EU"), Map.entry("NO", "EU"), Map.entry("DK", "EU"),
            Map.entry("FI", "EU"), Map.entry("PT", "EU"), Map.entry("IE", "EU"),
            Map.entry("PL", "EU"), Map.entry("CZ", "EU"), Map.entry("RO", "EU"),
            Map.entry("GR", "EU"), Map.entry("HU", "EU"),
            // Asia-Pacific
            Map.entry("JP", "APAC"), Map.entry("KR", "APAC"), Map.entry("AU", "APAC"),
            Map.entry("NZ", "APAC"), Map.entry("SG", "APAC"), Map.entry("IN", "APAC"),
            Map.entry("CN", "APAC"), Map.entry("HK", "APAC"), Map.entry("TW", "APAC"),
            Map.entry("TH", "APAC"), Map.entry("MY", "APAC"), Map.entry("ID", "APAC"),
            Map.entry("PH", "APAC"), Map.entry("VN", "APAC")
    );

    private final MeterRegistry meterRegistry;

    public GeoRouteGatewayFilterFactory(MeterRegistry meterRegistry) {
        super(Config.class);
        this.meterRegistry = meterRegistry;
    }

    @Override
    public GatewayFilter apply(Config config) {
        // Parse regions map from comma-separated "REGION=URI" pairs
        Map<String, URI> regionUris = parseRegions(config.getRegions());
        if (regionUris.isEmpty()) {
            log.warn("GeoRoute: no regions configured — filter will pass through all requests");
            return (exchange, chain) -> chain.filter(exchange);
        }

        String defaultRegion = config.getDefaultRegion() != null && !config.getDefaultRegion().isBlank()
                ? config.getDefaultRegion() : "US";
        String geoDbPath = config.getGeoDbPath() != null && !config.getGeoDbPath().isBlank()
                ? config.getGeoDbPath() : "classpath:GeoLite2-Country.mmdb";
        int cacheSize = config.getCacheSize() > 0 ? config.getCacheSize() : 10000;

        // Create the GeoIP resolver (loads database once)
        GeoIpResolver resolver = new GeoIpResolver(geoDbPath, cacheSize);

        log.info("GeoRoute filter configured: regions={} defaultRegion={} dbAvailable={} cacheSize={}",
                regionUris.keySet(), defaultRegion, resolver.isDatabaseAvailable(), cacheSize);

        Counter routedCounter = Counter.builder("routify.filter.geo_route.routed")
                .description("Requests routed to a geo-specific upstream")
                .register(meterRegistry);
        Counter fallbackCounter = Counter.builder("routify.filter.geo_route.fallback")
                .description("Requests that fell back to default region or original upstream")
                .register(meterRegistry);

        return (exchange, chain) -> {
            // 1. Resolve client IP
            InetAddress clientIp = resolveClientIp(exchange.getRequest());
            if (clientIp == null) {
                log.debug("GeoRoute: unable to resolve client IP — passing through");
                fallbackCounter.increment();
                return chain.filter(exchange);
            }

            // 2. Look up country code
            String countryCode = resolver.resolveCountry(clientIp).orElse(null);

            // 3. Map country → region
            String region;
            if (countryCode != null) {
                region = DEFAULT_COUNTRY_TO_REGION.getOrDefault(countryCode, defaultRegion);
            } else {
                region = defaultRegion;
                log.debug("GeoRoute: no country for {} — using default region '{}'",
                        clientIp.getHostAddress(), defaultRegion);
            }

            // 4. Look up region URI
            URI regionUri = regionUris.get(region);
            if (regionUri == null) {
                // Region not in map — try the default region as a last resort
                regionUri = regionUris.get(defaultRegion);
            }

            // 5. Inject X-Geo-Region header
            ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                    .header(X_GEO_REGION, region)
                    .build();
            var mutatedExchange = exchange.mutate().request(mutatedRequest).build();

            // Also set it on the response
            mutatedExchange.getResponse().beforeCommit(() -> {
                mutatedExchange.getResponse().getHeaders().set(X_GEO_REGION, region);
                return reactor.core.publisher.Mono.empty();
            });

            if (regionUri != null) {
                // 6. Rewrite the upstream URI
                URI originalUri = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
                if (originalUri == null) {
                    URI requestUri = exchange.getRequest().getURI();
                    URI newUri = UriComponentsBuilder.fromUri(requestUri)
                            .scheme(regionUri.getScheme())
                            .host(regionUri.getHost())
                            .port(regionUri.getPort())
                            .build(true).toUri();
                    mutatedExchange.getAttributes().put(
                            ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, newUri);
                    log.debug("GeoRoute: {} → region '{}' → upstream '{}'",
                            clientIp.getHostAddress(), region, newUri);
                } else {
                    boolean encoded = ServerWebExchangeUtils.containsEncodedParts(originalUri);
                    URI newUri = UriComponentsBuilder.fromUri(originalUri)
                            .scheme(regionUri.getScheme())
                            .host(regionUri.getHost())
                            .port(regionUri.getPort())
                            .build(encoded).toUri();
                    mutatedExchange.getAttributes().put(
                            ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, newUri);
                    log.debug("GeoRoute: {} → region '{}' → rewrite '{}' → '{}'",
                            clientIp.getHostAddress(), region, originalUri, newUri);
                }
                routedCounter.increment();
            } else {
                log.debug("GeoRoute: region '{}' not in regions map, default '{}' also not found — passing through",
                        region, defaultRegion);
                fallbackCounter.increment();
            }

            return chain.filter(mutatedExchange);
        };
    }

    /**
     * Resolves the client IP from the request. Checks {@code X-Forwarded-For}
     * first, falls back to TCP remote address.
     */
    private static InetAddress resolveClientIp(ServerHttpRequest request) {
        String xff = request.getHeaders().getFirst(X_FORWARDED_FOR);
        if (xff != null && !xff.isBlank()) {
            // Use the leftmost entry (the original client IP)
            String ip = xff.split(",")[0].strip();
            try {
                return InetAddress.getByName(ip);
            } catch (UnknownHostException e) {
                log.debug("GeoRoute: invalid IP in X-Forwarded-For: {}", ip);
            }
        }
        InetSocketAddress remoteAddress = request.getRemoteAddress();
        return remoteAddress != null ? remoteAddress.getAddress() : null;
    }

    /**
     * Parses a comma-separated "REGION=URI" string into a map.
     * Example: "US=https://us.api.example.com,EU=https://eu.api.example.com"
     */
    static Map<String, URI> parseRegions(String regions) {
        if (regions == null || regions.isBlank()) {
            return Map.of();
        }
        Map<String, URI> map = new LinkedHashMap<>();
        for (String entry : regions.split(",")) {
            String trimmed = entry.strip();
            int eqIdx = trimmed.indexOf('=');
            if (eqIdx <= 0 || eqIdx >= trimmed.length() - 1) {
                log.warn("GeoRoute: invalid region entry '{}' — expected 'REGION=URI'", trimmed);
                continue;
            }
            String regionKey = trimmed.substring(0, eqIdx).strip().toUpperCase(Locale.ROOT);
            String uriStr = trimmed.substring(eqIdx + 1).strip();
            try {
                map.put(regionKey, URI.create(uriStr));
            } catch (IllegalArgumentException e) {
                log.warn("GeoRoute: invalid URI '{}' for region '{}': {}", uriStr, regionKey, e.getMessage());
            }
        }
        return Map.copyOf(map);
    }

    @Data
    public static class Config {
        /**
         * Comma-separated region→URI pairs.
         * Example: "US=https://us.api.example.com,EU=https://eu.api.example.com"
         */
        private String regions = "";
        /** Fallback region when GeoIP lookup fails. Default: "US". */
        private String defaultRegion = "US";
        /** Path to the MaxMind GeoLite2-Country.mmdb file. Default: classpath:GeoLite2-Country.mmdb */
        private String geoDbPath = "classpath:GeoLite2-Country.mmdb";
        /** LRU cache size for IP → country lookups. Default: 10000. */
        private int cacheSize = 10000;
    }
}

