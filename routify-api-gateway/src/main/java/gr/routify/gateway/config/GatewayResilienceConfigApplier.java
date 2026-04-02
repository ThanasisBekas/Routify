package gr.routify.gateway.config;

import gr.routify.gateway.certificate.CertificateFileWatcher;
import gr.routify.gateway.certificate.CertificateStoreProperties;
import gr.routify.gateway.certificate.CertificateVaultLoader;
import gr.routify.gateway.net.HttpClientProperties;
import gr.routify.gateway.net.ProxyProperties;
import gr.routify.gateway.net.WebClientRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Applies live gateway configuration (loaded from the DB via {@link GatewayConfigLoader})
 * to the Resilience4J {@link CircuitBreakerRegistry} and {@link TimeLimiterRegistry}.
 *
 * <p>This closes the feedback loop between the Routify dashboard (where admins save circuit
 * breaker / retry / timeout defaults) and the actual Resilience4J registry that SCG's
 * {@code CircuitBreaker} filter uses at runtime.
 *
 * <h3>Update strategy</h3>
 * <p>Resilience4J registries support {@code addConfiguration(name, config)} which replaces
 * the named configuration entry. Any circuit breaker instance that was already created under
 * that name must be removed ({@code registry.remove(name)}) so that SCG creates a fresh
 * instance from the updated config on the next request. This is safe because:
 * <ul>
 *   <li>A gateway route reload ({@link gr.routify.gateway.routing.DynamicRouteRefreshListener})
 *       typically follows a config-change Kafka event, which causes SCG to re-evaluate all
 *       routes and recreate their filter chains (including circuit breakers).</li>
 *   <li>Any in-flight requests using the old instance complete normally before the instance
 *       is garbage-collected.</li>
 * </ul>
 *
 * <h3>Registered instance names</h3>
 * <ul>
 *   <li>{@code "default-cb"} — the primary named configuration registered in the Resilience4j
 *       registry. Routes that specify {@code CIRCUIT_BREAKER} without an explicit name fall back
 *       to the registry's built-in default config, which cannot be replaced via
 *       {@code addConfiguration} (Resilience4j forbids the name {@code "default"} there).
 *       Existing {@code "default"} <em>instances</em> are evicted via {@code registry.remove()}
 *       so that SCG recreates them from the updated named config on the next request.</li>
 * </ul>
 */
@Slf4j
@Component
public class GatewayResilienceConfigApplier {

    /**
     * Named configuration entries managed by this applier.
     * <p><strong>Important:</strong> Resilience4j reserves the name {@code "default"} for its
     * own built-in default and throws {@link IllegalArgumentException} if you try to register
     * a configuration under that name via {@code addConfiguration()}. Therefore only
     * {@code "default-cb"} is listed here. The {@code "default"} <em>instance</em> is still
     * evicted (via {@code registry.remove()}) so SCG recreates it on the next request.
     */
    private static final List<String> MANAGED_NAMES = List.of("default-cb");

    /**
     * Instance names that are evicted from the registry so SCG recreates them from the
     * updated configuration. This includes {@code "default"} even though we cannot register
     * a named <em>config</em> under that name.
     */
    private static final List<String> EVICT_NAMES = List.of("default", "default-cb");

    private final CircuitBreakerRegistry    circuitBreakerRegistry;
    private final TimeLimiterRegistry       timeLimiterRegistry;
    private final CertificateStoreProperties certificateStoreProperties;
    private final CertificateFileWatcher    certificateFileWatcher;
    private final CertificateVaultLoader    certificateVaultLoader;
    private final ProxyProperties           globalProxyProperties;
    private final HttpClientProperties      globalHttpClientProperties;
    private final WebClientRegistry         webClientRegistry;

    public GatewayResilienceConfigApplier(
            CircuitBreakerRegistry circuitBreakerRegistry,
            TimeLimiterRegistry timeLimiterRegistry,
            CertificateStoreProperties certificateStoreProperties,
            CertificateFileWatcher certificateFileWatcher,
            CertificateVaultLoader certificateVaultLoader,
            @Qualifier("globalProxyProperties")      ProxyProperties globalProxyProperties,
            @Qualifier("globalHttpClientProperties") HttpClientProperties globalHttpClientProperties,
            WebClientRegistry webClientRegistry) {
        this.circuitBreakerRegistry    = circuitBreakerRegistry;
        this.timeLimiterRegistry       = timeLimiterRegistry;
        this.certificateStoreProperties = certificateStoreProperties;
        this.certificateFileWatcher    = certificateFileWatcher;
        this.certificateVaultLoader    = certificateVaultLoader;
        this.globalProxyProperties     = globalProxyProperties;
        this.globalHttpClientProperties = globalHttpClientProperties;
        this.webClientRegistry         = webClientRegistry;
    }

    /**
     * Reads {@code circuitBreakerDefaults} and {@code resilienceDefaults} sections from the
     * live gateway config map and applies them to the Resilience4J registries.
     *
     * <p>If a section is absent or empty the registry is left unchanged so that startup
     * defaults from {@code application.yml} remain in effect.
     *
     * @param config the full gateway config map (as loaded from the DB by {@link GatewayConfigLoader})
     */
    @SuppressWarnings("unchecked")
    public void apply(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            log.debug("GatewayResilienceConfigApplier: config is empty — keeping registry defaults");
            return;
        }

        applyCircuitBreakerDefaults(asMap(config.get("circuitBreakerDefaults")));
        applyTimeLimiterDefaults(asMap(config.get("resilienceDefaults")));
        applyTlsConfig(asMap(config.get("tlsConfig")));
        applyNetworkingConfig(asMap(config.get("proxyConfig")), asMap(config.get("httpClientConfig")));
    }

    // ─── Circuit Breaker ──────────────────────────────────────────────────────

    private void applyCircuitBreakerDefaults(Map<String, Object> cb) {
        if (cb == null || cb.isEmpty()) {
            log.debug("GatewayResilienceConfigApplier: no circuitBreakerDefaults section — skipping");
            return;
        }

        try {
            CircuitBreakerConfig.Builder builder = CircuitBreakerConfig.custom();

            // Sliding window type and size
            String windowType = str(cb.get("slidingWindowType"), "COUNT_BASED");
            builder.slidingWindowType(
                    "TIME_BASED".equalsIgnoreCase(windowType)
                            ? CircuitBreakerConfig.SlidingWindowType.TIME_BASED
                            : CircuitBreakerConfig.SlidingWindowType.COUNT_BASED);

            int windowSize = toInt(cb.get("slidingWindowSize"), 100);
            builder.slidingWindowSize(windowSize);

            int minCalls = toInt(cb.get("minimumNumberOfCalls"), 10);
            builder.minimumNumberOfCalls(minCalls);

            double failureRate = toDouble(cb.get("failureRateThreshold"), 50.0);
            builder.failureRateThreshold((float) failureRate);

            double slowCallRate = toDouble(cb.get("slowCallRateThreshold"), 100.0);
            builder.slowCallRateThreshold((float) slowCallRate);

            long slowCallDurationMs = toLong(cb.get("slowCallDurationThresholdMs"), 60_000L);
            builder.slowCallDurationThreshold(Duration.ofMillis(slowCallDurationMs));

            String waitInOpen = str(cb.get("waitDurationInOpenState"), "10s");
            builder.waitDurationInOpenState(parseDuration(waitInOpen));

            int permittedHalfOpen = toInt(cb.get("permittedNumberOfCallsInHalfOpenState"), 3);
            builder.permittedNumberOfCallsInHalfOpenState(permittedHalfOpen);

            boolean autoTransition = toBool(cb.get("automaticTransitionFromOpenToHalfOpen"), true);
            builder.automaticTransitionFromOpenToHalfOpenEnabled(autoTransition);

            CircuitBreakerConfig cbConfig = builder.build();

            // Evict all managed instances first (including "default") so SCG recreates them
            for (String name : EVICT_NAMES) {
                circuitBreakerRegistry.remove(name);
            }
            // Register the named config only for non-reserved names ("default" is forbidden)
            for (String name : MANAGED_NAMES) {
                circuitBreakerRegistry.addConfiguration(name, cbConfig);
                log.debug("CircuitBreakerRegistry: updated config for '{}' — " +
                                "windowSize={} failureRate={}% waitInOpen={} minCalls={}",
                        name, windowSize, failureRate, waitInOpen, minCalls);
            }

            log.info("GatewayResilienceConfigApplier: CircuitBreaker defaults applied " +
                    "(windowSize={} failureRate={}% waitInOpen={})", windowSize, failureRate, waitInOpen);

        } catch (Exception e) {
            log.error("GatewayResilienceConfigApplier: failed to apply circuitBreakerDefaults: {}", e.getMessage(), e);
        }
    }

    // ─── Time Limiter ─────────────────────────────────────────────────────────

    private void applyTimeLimiterDefaults(Map<String, Object> rd) {
        if (rd == null || rd.isEmpty()) {
            log.debug("GatewayResilienceConfigApplier: no resilienceDefaults section — skipping");
            return;
        }

        try {
            String timeoutStr = str(rd.get("timeoutDuration"), "10s");
            boolean cancelRunning = toBool(rd.get("timeoutCancelRunningFuture"), true);

            TimeLimiterConfig tlConfig = TimeLimiterConfig.custom()
                    .timeoutDuration(parseDuration(timeoutStr))
                    .cancelRunningFuture(cancelRunning)
                    .build();

            for (String name : EVICT_NAMES) {
                timeLimiterRegistry.remove(name);
            }
            for (String name : MANAGED_NAMES) {
                timeLimiterRegistry.addConfiguration(name, tlConfig);
                log.debug("TimeLimiterRegistry: updated config for '{}' — timeout={} cancel={}",
                        name, timeoutStr, cancelRunning);
            }

            log.info("GatewayResilienceConfigApplier: TimeLimiter defaults applied " +
                    "(timeout={} cancelRunning={})", timeoutStr, cancelRunning);

        } catch (Exception e) {
            log.error("GatewayResilienceConfigApplier: failed to apply resilienceDefaults (timelimiter): {}", e.getMessage(), e);
        }
    }

    // ─── TLS / Certificate Sources ────────────────────────────────────────────

    /**
     * Applies the {@code tlsConfig} section from the live gateway config to
     * {@link CertificateStoreProperties} and triggers an immediate certificate reload
     * for <em>both</em> file-based sources (via {@link CertificateFileWatcher#reloadSources()})
     * and vault-backed sources (via {@link CertificateVaultLoader#loadVaultCertificates()}).
     *
     * <p>This ensures that when an admin saves a new TLS config (e.g. adds/removes a
     * file source or changes the expiry warning window) through the dashboard, the
     * gateway picks up the change without a restart — including certificates used by the
     * {@code AUTH_CERT_VAULT} and {@code CERT_VAULT_EXPIRY_CHECK} filters that rely on
     * vault-backed entries in the {@link gr.routify.gateway.certificate.CertificateRegistry}.
     */
    @SuppressWarnings("unchecked")
    private void applyTlsConfig(Map<String, Object> tls) {
        if (tls == null || tls.isEmpty()) {
            log.debug("GatewayResilienceConfigApplier: no tlsConfig section — skipping");
            return;
        }
        try {
            // expiryWarning
            String expiryWarning = str(tls.get("expiryWarning"), null);
            if (expiryWarning != null) {
                certificateStoreProperties.setExpiryWarning(parseDuration(expiryWarning));
            }

            // fileWatchInterval
            String fileWatchInterval = str(tls.get("fileWatchInterval"), null);
            if (fileWatchInterval != null) {
                certificateStoreProperties.setFileWatchInterval(parseDuration(fileWatchInterval));
            }

            // fileSources
            Object rawFileSources = tls.get("fileSources");
            if (rawFileSources instanceof List<?> rawList) {
                var fileSources = new ArrayList<CertificateStoreProperties.FileSource>();
                for (Object rawSrc : rawList) {
                    Map<String, Object> src = asMap(rawSrc);
                    if (src == null) continue;
                    var fs = new CertificateStoreProperties.FileSource();
                    fs.setLogicalId(str(src.get("logicalId"), null));
                    fs.setCertificatePath(str(src.get("certificatePath"), null));
                    fs.setPrivateKeyPath(str(src.get("privateKeyPath"), null));
                    fs.setPrivateKeyPassword(str(src.get("privateKeyPassword"), null));
                    Object watch = src.get("watchForChanges");
                    fs.setWatchForChanges(watch instanceof Boolean b ? b
                            : Boolean.parseBoolean(String.valueOf(watch)));
                    fileSources.add(fs);
                }
                certificateStoreProperties.setFileSources(fileSources);
            }

            // directorySources
            Object rawDirSources = tls.get("directorySources");
            if (rawDirSources instanceof List<?> rawList) {
                var dirSources = new ArrayList<CertificateStoreProperties.DirectorySource>();
                for (Object rawSrc : rawList) {
                    Map<String, Object> src = asMap(rawSrc);
                    if (src == null) continue;
                    var ds = new CertificateStoreProperties.DirectorySource();
                    ds.setDirectoryPath(str(src.get("directoryPath"), null));
                    ds.setLogicalId(str(src.get("logicalId"), null));
                    ds.setPrivateKeyPath(str(src.get("privateKeyPath"), null));
                    ds.setPrivateKeyPassword(str(src.get("privateKeyPassword"), null));
                    Object watch = src.get("watchForChanges");
                    ds.setWatchForChanges(watch instanceof Boolean b ? b
                            : Boolean.parseBoolean(String.valueOf(watch)));
                    dirSources.add(ds);
                }
                certificateStoreProperties.setDirectorySources(dirSources);
            }

            // Reload file/directory sources immediately so the new config takes effect
            certificateFileWatcher.reloadSources();

            // Reload vault-backed certificates so AUTH_CERT_VAULT and
            // CERT_VAULT_EXPIRY_CHECK filters pick up the latest material
            // from cert-vault. This is safe: CertificateRegistry.register()
            // deduplicates identical active certs, so already-loaded entries
            // are a no-op.
            try {
                certificateVaultLoader.loadVaultCertificates();
            } catch (Exception vaultEx) {
                log.warn("GatewayResilienceConfigApplier: vault certificate reload failed " +
                        "(cert-vault may be unavailable) — vault-backed certs will refresh " +
                        "on next Kafka event: {}", vaultEx.getMessage());
            }

            log.info("GatewayResilienceConfigApplier: TLS config applied — " +
                    "file sources and vault certificates reloaded");

        } catch (Exception e) {
            log.error("GatewayResilienceConfigApplier: failed to apply tlsConfig: {}", e.getMessage(), e);
        }
    }

    // ─── Networking (Proxy + HTTP Client) ─────────────────────────────────────

    /**
     * Applies {@code proxyConfig} and {@code httpClientConfig} sections from the live gateway
     * config to the shared {@link ProxyProperties} and {@link HttpClientProperties} beans,
     * then evicts all cached {@link WebClientRegistry} entries so the next request rebuilds
     * clients with the updated settings.
     */
    private void applyNetworkingConfig(Map<String, Object> proxy, Map<String, Object> http) {
        boolean changed = false;

        if (proxy != null && !proxy.isEmpty()) {
            try {
                boolean enabled = toBool(proxy.get("enabled"), false);
                if (enabled) {
                    String host = str(proxy.get("host"), null);
                    int    port = toInt(proxy.get("port"), 0);
                    if (host != null && port > 0) {
                        globalProxyProperties.setHost(host);
                        globalProxyProperties.setPort(port);
                        globalProxyProperties.setType(str(proxy.get("type"), "HTTP"));
                        globalProxyProperties.setUsername(str(proxy.get("username"), null));
                        // Preserve stored password unless a new non-blank one is provided
                        String newPass = str(proxy.get("password"), null);
                        if (newPass != null && !newPass.isBlank() && !newPass.startsWith("••")) {
                            globalProxyProperties.setPassword(newPass);
                        }
                        Object rawNph = proxy.get("nonProxyHosts");
                        if (rawNph instanceof List<?> nph) {
                            globalProxyProperties.setNonProxyHosts(
                                    nph.stream().map(Object::toString).toList());
                        }
                        log.info("GatewayResilienceConfigApplier: proxy applied — host={}:{} type={}",
                                host, port, globalProxyProperties.getType());
                    }
                } else {
                    // Proxy disabled — clear host so isProxyConfigured() returns false
                    globalProxyProperties.setHost(null);
                    globalProxyProperties.setPort(null);
                    log.info("GatewayResilienceConfigApplier: proxy disabled");
                }
                changed = true;
            } catch (Exception e) {
                log.error("GatewayResilienceConfigApplier: failed to apply proxyConfig: {}", e.getMessage(), e);
            }
        }

        if (http != null && !http.isEmpty()) {
            try {
                int maxConns = toInt(http.get("maxConnections"), globalHttpClientProperties.getMaxTotalConnections());
                if (maxConns > 0) globalHttpClientProperties.setMaxTotalConnections(maxConns);

                int maxPerRoute = toInt(http.get("maxConnectionsPerRoute"), globalHttpClientProperties.getMaxConnectionsPerRoute());
                if (maxPerRoute > 0) globalHttpClientProperties.setMaxConnectionsPerRoute(maxPerRoute);

                int connectMs = toInt(http.get("connectTimeoutMs"), globalHttpClientProperties.getConnectTimeoutMillis());
                if (connectMs > 0) globalHttpClientProperties.setConnectTimeoutMillis(connectMs);

                int responseMs = toInt(http.get("responseTimeoutMs"), globalHttpClientProperties.getSocketTimeoutMillis());
                if (responseMs > 0) globalHttpClientProperties.setSocketTimeoutMillis(responseMs);

                int acquireMs = toInt(http.get("acquireTimeoutMs"), globalHttpClientProperties.getRequestTimeoutMillis());
                if (acquireMs > 0) globalHttpClientProperties.setRequestTimeoutMillis(acquireMs);

                String maxIdle = str(http.get("maxIdleTime"), null);
                if (maxIdle != null) globalHttpClientProperties.setMaxIdleTime(maxIdle);

                String maxLife = str(http.get("maxLifeTime"), null);
                if (maxLife != null) globalHttpClientProperties.setMaxLifeTime(maxLife);

                globalHttpClientProperties.setCompressionEnabled(
                        toBool(http.get("compressionEnabled"), globalHttpClientProperties.isCompressionEnabled()));
                globalHttpClientProperties.setFollowRedirects(
                        toBool(http.get("followRedirects"), globalHttpClientProperties.isFollowRedirects()));
                globalHttpClientProperties.setWiretapEnabled(
                        toBool(http.get("wiretapEnabled"), globalHttpClientProperties.isWiretapEnabled()));

                log.info("GatewayResilienceConfigApplier: httpClientConfig applied — " +
                                "maxConns={} connectMs={} responseMs={} acquireMs={} " +
                                "maxIdle={} maxLife={} compression={} followRedirects={} wiretap={}",
                        maxConns, connectMs, responseMs, acquireMs,
                        globalHttpClientProperties.getMaxIdleTime(),
                        globalHttpClientProperties.getMaxLifeTime(),
                        globalHttpClientProperties.isCompressionEnabled(),
                        globalHttpClientProperties.isFollowRedirects(),
                        globalHttpClientProperties.isWiretapEnabled());
                changed = true;
            } catch (Exception e) {
                log.error("GatewayResilienceConfigApplier: failed to apply httpClientConfig: {}", e.getMessage(), e);
            }
        }

        if (changed) {
            // Evict all cached WebClient instances so they rebuild with the new networking config
            webClientRegistry.evictAll();
        }
    }

    // ─── Duration parsing ─────────────────────────────────────────────────────

    /**
     * Parses a duration string in common gateway notation:
     * <ul>
     *   <li>{@code "10s"} → 10 seconds</li>
     *   <li>{@code "500ms"} → 500 milliseconds</li>
     *   <li>{@code "2m"} → 2 minutes</li>
     *   <li>{@code "1h"} → 1 hour</li>
     *   <li>{@code "PT10S"} (ISO-8601) → 10 seconds</li>
     *   <li>plain integer string → milliseconds</li>
     * </ul>
     *
     * @param value the duration string (never null — callers supply a default)
     * @return parsed {@link Duration}
     */
    static Duration parseDuration(String value) {
        if (value == null || value.isBlank()) return Duration.ofSeconds(10);

        String v = value.trim();

        // ISO-8601 format (e.g. PT10S, PT0.5S)
        if (v.startsWith("PT") || v.startsWith("P")) {
            try {
                return Duration.parse(v);
            } catch (Exception ignored) {
                // fall through
            }
        }

        // Plain number → milliseconds
        try {
            return Duration.ofMillis(Long.parseLong(v));
        } catch (NumberFormatException ignored) {
            // fall through to unit-suffix parsing
        }

        // Unit-suffix formats
        if (v.endsWith("ms")) {
            try { return Duration.ofMillis(Long.parseLong(v.substring(0, v.length() - 2).trim())); }
            catch (NumberFormatException ignored) { /* fall through */ }
        }
        if (v.endsWith("s")) {
            try { return Duration.ofSeconds(Long.parseLong(v.substring(0, v.length() - 1).trim())); }
            catch (NumberFormatException ignored) { /* fall through */ }
        }
        if (v.endsWith("m")) {
            try { return Duration.ofMinutes(Long.parseLong(v.substring(0, v.length() - 1).trim())); }
            catch (NumberFormatException ignored) { /* fall through */ }
        }
        if (v.endsWith("h")) {
            try { return Duration.ofHours(Long.parseLong(v.substring(0, v.length() - 1).trim())); }
            catch (NumberFormatException ignored) { /* fall through */ }
        }
        if (v.endsWith("d")) {
            try { return Duration.ofDays(Long.parseLong(v.substring(0, v.length() - 1).trim())); }
            catch (NumberFormatException ignored) { /* fall through */ }
        }

        log.warn("GatewayResilienceConfigApplier: could not parse duration '{}' — using 10s default", value);
        return Duration.ofSeconds(10);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            try { return (Map<String, Object>) m; } catch (ClassCastException e) { return null; }
        }
        return null;
    }

    private static String str(Object v, String defaultValue) {
        if (v == null) return defaultValue;
        String s = v.toString().trim();
        return s.isBlank() ? defaultValue : s;
    }

    private static int toInt(Object v, int defaultValue) {
        if (v == null) return defaultValue;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString().trim()); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    private static long toLong(Object v, long defaultValue) {
        if (v == null) return defaultValue;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString().trim()); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    private static double toDouble(Object v, double defaultValue) {
        if (v == null) return defaultValue;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString().trim()); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    private static boolean toBool(Object v, boolean defaultValue) {
        if (v == null) return defaultValue;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString().trim());
    }
}

