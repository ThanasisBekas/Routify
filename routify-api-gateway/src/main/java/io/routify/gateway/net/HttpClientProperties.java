package io.routify.gateway.net;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Connection pool and timeout settings for Reactor Netty {@link org.springframework.web.reactive.function.client.WebClient}
 * instances created by {@link WebClientFactory}.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HttpClientProperties {

    /** Maximum total connections across all routes. */
    @Builder.Default
    private Integer maxTotalConnections = 500;

    /** Maximum connections per individual route/host. */
    @Builder.Default
    private Integer maxConnectionsPerRoute = 50;

    /** Timeout for acquiring a connection from the pool (ms). */
    @Builder.Default
    private int requestTimeoutMillis = 45000;

    /** TCP connect timeout (ms). */
    @Builder.Default
    private int connectTimeoutMillis = 6000;

    /** Socket read/response timeout (ms). */
    @Builder.Default
    private int socketTimeoutMillis = 10000;

    /** Max idle time for a pooled connection before eviction. e.g. "20s" */
    @Builder.Default
    private String maxIdleTime = "20s";

    /** Max lifetime for a pooled connection before eviction. e.g. "60s" */
    @Builder.Default
    private String maxLifeTime = "60s";

    /** Enable GZip compression on upstream requests. */
    @Builder.Default
    private boolean compressionEnabled = false;

    /** Automatically follow HTTP 3xx redirects. */
    @Builder.Default
    private boolean followRedirects = false;

    /** Enable Reactor Netty wire-tap (logs raw bytes — development only). */
    @Builder.Default
    private boolean wiretapEnabled = false;
}

