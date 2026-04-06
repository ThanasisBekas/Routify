package io.routify.gateway.filter;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * Gateway filter factory that applies API versioning to every request passing through
 * the route it is attached to.
 *
 * <h3>Strategies (controlled by the {@code strategy} config key):</h3>
 * <ul>
 *   <li>{@code HEADER} (default) — injects the version as a request header
 *       (header name controlled by {@code versionHeader}, default {@code X-Api-Version}).</li>
 *   <li>{@code QUERY} — appends the version as a query parameter
 *       (param name controlled by {@code versionParam}, default {@code version}).</li>
 *   <li>{@code PATH} — rewrites the first path segment to the configured
 *       {@code versionPrefix} (e.g. {@code /v2}) by prepending it before the existing path.
 *       Idempotent: if the path already starts with {@code versionPrefix} it is not added again.</li>
 * </ul>
 *
 * <h3>Config params:</h3>
 * <ul>
 *   <li>{@code version}       — the version string to inject, e.g. {@code "2"} or {@code "v2"}</li>
 *   <li>{@code strategy}      — {@code HEADER} | {@code QUERY} | {@code PATH} (default: {@code HEADER})</li>
 *   <li>{@code versionHeader} — header name for HEADER strategy (default: {@code X-Api-Version})</li>
 *   <li>{@code versionParam}  — query param name for QUERY strategy (default: {@code version})</li>
 *   <li>{@code versionPrefix} — path prefix for PATH strategy, e.g. {@code /v2} (default: {@code /} + version)</li>
 * </ul>
 *
 * <p>Filter type: {@code API_VERSIONING}
 */
@Slf4j
@Component
public class ApiVersioningGatewayFilterFactory
        extends AbstractGatewayFilterFactory<ApiVersioningGatewayFilterFactory.Config> {

    public ApiVersioningGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        String version       = config.getVersion()       != null ? config.getVersion()       : "";
        String strategy      = config.getStrategy()      != null ? config.getStrategy()       : "HEADER";
        String versionHeader = config.getVersionHeader() != null ? config.getVersionHeader()  : "X-Api-Version";
        String versionParam  = config.getVersionParam()  != null ? config.getVersionParam()   : "version";
        String versionPrefix = config.getVersionPrefix() != null ? config.getVersionPrefix()  : "/" + version;

        return (exchange, chain) -> {
            if (version.isBlank()) {
                log.warn("ApiVersioning: no 'version' configured — passing through unchanged");
                return chain.filter(exchange);
            }

            ServerHttpRequest req = exchange.getRequest();
            ServerHttpRequest mutated = switch (strategy.toUpperCase()) {
                case "HEADER" -> {
                    log.debug("ApiVersioning: injecting header '{}' = '{}'", versionHeader, version);
                    yield req.mutate().header(versionHeader, version).build();
                }
                case "QUERY" -> {
                    log.debug("ApiVersioning: appending query param '{}' = '{}'", versionParam, version);
                    URI newUri = UriComponentsBuilder.fromUri(req.getURI())
                            .replaceQueryParam(versionParam, version)
                            .build(true).toUri();
                    yield req.mutate().uri(newUri).build();
                }
                case "PATH" -> {
                    String path = req.getURI().getRawPath();
                    if (!path.startsWith(versionPrefix)) {
                        String newPath = versionPrefix + (path.startsWith("/") ? path : "/" + path);
                        URI newUri = UriComponentsBuilder.fromUri(req.getURI())
                                .replacePath(newPath)
                                .build(true).toUri();
                        log.debug("ApiVersioning: rewriting path '{}' → '{}'", path, newPath);
                        yield req.mutate().uri(newUri).build();
                    } else {
                        log.debug("ApiVersioning: path '{}' already starts with '{}' — skipping", path, versionPrefix);
                        yield req;
                    }
                }
                default -> {
                    log.warn("ApiVersioning: unknown strategy '{}' — passing through unchanged", strategy);
                    yield req;
                }
            };

            return chain.filter(exchange.mutate().request(mutated).build());
        };
    }

    @Data
    public static class Config {
        /** The version string, e.g. "2" or "v2". Required. */
        private String version;
        /** HEADER | QUERY | PATH. Default: HEADER. */
        private String strategy      = "HEADER";
        /** Request header name used by HEADER strategy. Default: X-Api-Version. */
        private String versionHeader = "X-Api-Version";
        /** Query param name used by QUERY strategy. Default: version. */
        private String versionParam  = "version";
        /** Path prefix used by PATH strategy. Default: "/" + version. */
        private String versionPrefix;
    }
}

