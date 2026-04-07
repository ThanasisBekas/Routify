package io.routify.gateway.filter.routing;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import com.maxmind.geoip2.model.CountryResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Resolves an {@link InetAddress} to a two-letter ISO country code using the
 * MaxMind GeoIP2/GeoLite2 Country database.
 *
 * <p>The database is loaded once at construction time and kept in memory.
 * An LRU {@link Cache} (Caffeine) avoids repeated database lookups for the
 * same IP address. Cache entries have no TTL — GeoIP data changes
 * infrequently; the cache clears on gateway restart.
 *
 * <p>If the database file cannot be found or loaded, the resolver operates
 * in <em>fallback mode</em> — every lookup returns {@link Optional#empty()},
 * causing the filter to fall back to its default region.
 *
 * @see GeoRouteGatewayFilterFactory
 */
@Slf4j
public final class GeoIpResolver implements Closeable {

    private final DatabaseReader databaseReader;
    private final Cache<InetAddress, Optional<String>> cache;

    /**
     * Creates a new resolver.
     *
     * @param geoDbPath  path to the MaxMind {@code .mmdb} file. If it starts with
     *                   {@code classpath:}, the resource is loaded from the classpath.
     * @param cacheSize  maximum number of IP → country entries to keep in the LRU cache
     */
    public GeoIpResolver(String geoDbPath, int cacheSize) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(cacheSize)
                .build();
        this.databaseReader = loadDatabase(geoDbPath);
        if (this.databaseReader != null) {
            log.info("GeoIpResolver: MaxMind database loaded from '{}' (cache size: {})", geoDbPath, cacheSize);
        } else {
            log.warn("GeoIpResolver: MaxMind database NOT loaded — all lookups will return empty (fallback mode)");
        }
    }

    /**
     * Resolves the given IP address to a two-letter ISO country code.
     *
     * @param address the client IP address
     * @return the country code (e.g. {@code "US"}, {@code "DE"}), or empty
     *         if the lookup fails or the database is unavailable
     */
    public Optional<String> resolveCountry(InetAddress address) {
        if (databaseReader == null || address == null) {
            return Optional.empty();
        }
        return cache.get(address, this::doLookup);
    }

    private Optional<String> doLookup(InetAddress address) {
        try {
            CountryResponse response = databaseReader.country(address);
            String isoCode = response.getCountry().getIsoCode();
            if (isoCode != null && !isoCode.isBlank()) {
                log.trace("GeoIpResolver: {} → {}", address.getHostAddress(), isoCode);
                return Optional.of(isoCode);
            }
        } catch (GeoIp2Exception e) {
            log.debug("GeoIpResolver: no GeoIP result for {} — {}", address.getHostAddress(), e.getMessage());
        } catch (IOException e) {
            log.warn("GeoIpResolver: I/O error looking up {} — {}", address.getHostAddress(), e.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public void close() {
        if (databaseReader != null) {
            try {
                databaseReader.close();
            } catch (IOException e) {
                log.warn("GeoIpResolver: error closing database reader: {}", e.getMessage());
            }
        }
    }

    /**
     * Loads the MaxMind database from the given path.
     * Returns {@code null} on failure (the resolver runs in fallback mode).
     */
    private static DatabaseReader loadDatabase(String geoDbPath) {
        if (geoDbPath == null || geoDbPath.isBlank()) {
            return null;
        }
        try {
            if (geoDbPath.startsWith("classpath:")) {
                String resource = geoDbPath.substring("classpath:".length());
                InputStream is = GeoIpResolver.class.getClassLoader().getResourceAsStream(resource);
                if (is == null) {
                    log.warn("GeoIpResolver: classpath resource '{}' not found", resource);
                    return null;
                }
                return new DatabaseReader.Builder(is).build();
            } else {
                Path path = Path.of(geoDbPath);
                if (!Files.exists(path)) {
                    log.warn("GeoIpResolver: file '{}' not found", geoDbPath);
                    return null;
                }
                return new DatabaseReader.Builder(path.toFile()).build();
            }
        } catch (IOException e) {
            log.error("GeoIpResolver: failed to load MaxMind database from '{}': {}", geoDbPath, e.getMessage());
            return null;
        }
    }

    /** Returns {@code true} if the underlying MaxMind database is loaded. */
    public boolean isDatabaseAvailable() {
        return databaseReader != null;
    }
}

