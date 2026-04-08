package io.routify.gateway.net;

import io.routify.gateway.ssl.SSLContextProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry of lazily-created, per-provider {@link WebClient} instances.
 *
 * <p>Cache keys are caller-supplied strings (e.g. {@code "token:myProvider"},
 * {@code "verify:myProvider"}) so token-acquisition and verification clients
 * remain independent. Supports targeted and full eviction for use after a
 * config refresh.
 */
@Slf4j
@Component
public class WebClientRegistry {

    private final ConcurrentHashMap<String, WebClient> clients = new ConcurrentHashMap<>();

    /**
     * Returns a cached {@link WebClient} for {@code cacheKey}, creating it on first access.
     *
     * @param cacheKey             unique key (e.g. {@code "token:providerName"}, {@code "verify:providerName"})
     * @param config               SSL/proxy/timeout configuration; {@code uri} is used for base-URL extraction
     * @param proxyProperties      optional proxy settings
     * @param httpClientProperties connection pool and timeout settings
     * @return a configured, cached {@link WebClient}
     */
    public WebClient get(String cacheKey,
                         SSLContextProperties config,
                         ProxyProperties proxyProperties,
                         HttpClientProperties httpClientProperties) {
        return clients.computeIfAbsent(cacheKey, key -> {
            String baseUrl = extractBaseUrl(config.getUri());
            log.info("Creating WebClient: key='{}', baseUrl='{}'", cacheKey, baseUrl);
            return WebClientFactory.createWebClient(config, proxyProperties, httpClientProperties, baseUrl);
        });
    }

    /**
     * Returns a cached {@link WebClient} for a direct URI, creating it on first access.
     *
     * <p>This variant is used when no {@link SSLContextProperties} or proxy config is
     * available — e.g. for direct OAuth2 provider configs resolved from gateway config
     * refs (P-25 {@code DOWNSTREAM_OAUTH2_PROVIDER}).
     *
     * @param cacheKey unique key (e.g. {@code "direct-cc:clientId"})
     * @param uri      the full token endpoint URI
     * @return a configured, cached {@link WebClient}
     */
    public WebClient getForUri(String cacheKey, String uri) {
        return clients.computeIfAbsent(cacheKey, key -> {
            String baseUrl = extractBaseUrl(uri);
            log.info("Creating WebClient (direct URI): key='{}', baseUrl='{}'", cacheKey, baseUrl);
            return WebClientFactory.createWebClient(
                    null, null, new HttpClientProperties(), baseUrl);
        });
    }

    /**
     * Evicts a cached client by its exact key, forcing recreation on the next {@link #get} call.
     *
     * @param cacheKey the key to evict
     */
    public void evict(String cacheKey) {
        if (clients.remove(cacheKey) != null) {
            log.info("Evicted WebClient for key='{}'", cacheKey);
        }
    }

    /**
     * Evicts all cached clients whose key ends with {@code ":<providerName>"}.
     *
     * @param providerName the provider name suffix to match
     * @return the number of entries evicted
     */
    public int evictByProviderName(String providerName) {
        String suffix = ":" + providerName;
        var evicted = clients.keySet().stream()
                .filter(key -> key.endsWith(suffix))
                .toList();
        evicted.forEach(clients::remove);
        if (!evicted.isEmpty()) {
            log.info("Evicted {} WebClient(s) for provider '{}': {}", evicted.size(), providerName, evicted);
        }
        return evicted.size();
    }

    /**
     * Evicts all cached clients, forcing recreation on the next {@link #get} call.
     * Intended for use after a full config refresh.
     */
    public void evictAll() {
        int count = clients.size();
        clients.clear();
        log.info("Evicted all {} cached WebClient(s)", count);
    }

    /**
     * Extracts {@code scheme://host} or {@code scheme://host:port} from a full URI string.
     */
    static String extractBaseUrl(String uriString) {
        URI uri = URI.create(uriString);
        return uri.getPort() != -1
                ? "%s://%s:%d".formatted(uri.getScheme(), uri.getHost(), uri.getPort())
                : "%s://%s".formatted(uri.getScheme(), uri.getHost());
    }
}

