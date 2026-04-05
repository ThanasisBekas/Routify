package io.routify.gateway.net;

import io.routify.gateway.ssl.SSLContextProperties;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.transport.ProxyProvider;

import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Static factory for building Reactor Netty {@link WebClient} instances
 * with SSL, connection pool, timeout, and optional proxy configuration.
 */
@Slf4j
@UtilityClass
public class WebClientFactory {

    /**
     * Creates a {@link WebClient} configured with SSL, timeouts, connection pool, and optional proxy.
     *
     * @param sslContextProperties SSL/TLS configuration
     * @param proxyProperties      optional proxy configuration
     * @param httpClientProperties connection timeouts and pool settings
     * @param baseUrl              base URL for the WebClient
     * @return a configured {@link WebClient}
     */
    public static WebClient createWebClient(SSLContextProperties sslContextProperties,
                                            ProxyProperties proxyProperties,
                                            HttpClientProperties httpClientProperties,
                                            String baseUrl) {

        Duration maxIdle = parseDuration(httpClientProperties.getMaxIdleTime(), Duration.ofSeconds(20));
        Duration maxLife = parseDuration(httpClientProperties.getMaxLifeTime(), Duration.ofSeconds(60));

        ConnectionProvider connectionProvider = ConnectionProvider.builder("routify-oauth2-pool")
                .maxConnections(httpClientProperties.getMaxTotalConnections())
                .pendingAcquireMaxCount(httpClientProperties.getMaxTotalConnections() * 2)
                .pendingAcquireTimeout(Duration.ofMillis(httpClientProperties.getRequestTimeoutMillis()))
                .maxIdleTime(maxIdle)
                .maxLifeTime(maxLife)
                .metrics(true)
                .build();

        log.info("Creating WebClient: maxConnections={}, connectTimeout={}ms, responseTimeout={}ms, " +
                        "maxIdle={}, maxLife={}, compression={}, followRedirects={}, wiretap={}",
                httpClientProperties.getMaxTotalConnections(),
                httpClientProperties.getConnectTimeoutMillis(),
                httpClientProperties.getSocketTimeoutMillis(),
                httpClientProperties.getMaxIdleTime(),
                httpClientProperties.getMaxLifeTime(),
                httpClientProperties.isCompressionEnabled(),
                httpClientProperties.isFollowRedirects(),
                httpClientProperties.isWiretapEnabled());

        HttpClient httpClient = HttpClient.create(connectionProvider)
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        httpClientProperties.getConnectTimeoutMillis())
                .responseTimeout(Duration.ofMillis(httpClientProperties.getSocketTimeoutMillis()));

        if (httpClientProperties.isCompressionEnabled()) {
            httpClient = httpClient.compress(true);
        }
        if (httpClientProperties.isFollowRedirects()) {
            httpClient = httpClient.followRedirect(true);
        }
        if (httpClientProperties.isWiretapEnabled()) {
            httpClient = httpClient.wiretap(true);
        }

        if (sslContextProperties != null && sslContextProperties.isSSLConfigured()) {
            httpClient = httpClient.secure(spec -> {
                try {
                    SslContext sslContext = buildNettySslContext(sslContextProperties);
                    var builder = spec.sslContext(sslContext);
                    if (sslContextProperties.isSkipHostnameVerification()) {
                        builder.handlerConfigurator(handler -> {
                            var params = handler.engine().getSSLParameters();
                            params.setEndpointIdentificationAlgorithm(null);
                            handler.engine().setSSLParameters(params);
                        });
                        log.warn("WebClient SSL: hostname verification DISABLED for baseUrl='{}'", baseUrl);
                    }
                } catch (Exception e) {
                    log.error("Failed to configure SSL for WebClient", e);
                    throw new RuntimeException("Failed to configure SSL for WebClient", e);
                }
            });
        } else if (sslContextProperties != null && sslContextProperties.isSkipHostnameVerification()) {
            log.warn("WebClient SSL: hostname verification DISABLED (no custom cert) for baseUrl='{}'", baseUrl);
            httpClient = httpClient.secure(spec -> {
                try {
                    spec.sslContext(SslContextBuilder.forClient()
                                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                                    .build())
                            .handlerConfigurator(handler -> {
                                var params = handler.engine().getSSLParameters();
                                params.setEndpointIdentificationAlgorithm(null);
                                handler.engine().setSSLParameters(params);
                            });
                } catch (Exception e) {
                    log.error("Failed to configure insecure SSL for WebClient", e);
                    throw new RuntimeException("Failed to configure insecure SSL for WebClient", e);
                }
            });
        }

        if (proxyProperties != null && proxyProperties.isProxyConfigured()) {
            final ProxyProperties proxy = proxyProperties;
            httpClient = httpClient.proxy(proxySpec -> {
                ProxyProvider.Proxy proxyType = resolveProxyType(proxy.getType());
                var spec = proxySpec.type(proxyType)
                        .host(proxy.getHost())
                        .port(proxy.getPort());
                if (proxy.getUsername() != null && !proxy.getUsername().isBlank()) {
                    spec.username(proxy.getUsername())
                        .password(u -> proxy.getPassword() != null ? proxy.getPassword() : "");
                }
                List<String> nonProxyHosts = proxy.getNonProxyHosts();
                if (nonProxyHosts != null && !nonProxyHosts.isEmpty()) {
                    spec.nonProxyHosts(String.join("|", nonProxyHosts));
                }
            });
        }

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .baseUrl(baseUrl)
                .build();
    }

    /** Creates a {@link WebClient} without a fixed base URL. */
    public static WebClient createWebClient(SSLContextProperties sslContextProperties,
                                            ProxyProperties proxyProperties,
                                            HttpClientProperties httpClientProperties) {
        return createWebClient(sslContextProperties, proxyProperties, httpClientProperties, "");
    }

    private static ProxyProvider.Proxy resolveProxyType(String type) {
        if (type == null) return ProxyProvider.Proxy.HTTP;
        return switch (type.toUpperCase()) {
            case "SOCKS5" -> ProxyProvider.Proxy.SOCKS5;
            case "SOCKS4" -> ProxyProvider.Proxy.SOCKS4;
            default       -> ProxyProvider.Proxy.HTTP;
        };
    }

    private static Duration parseDuration(String value, Duration fallback) {
        if (value == null || value.isBlank()) return fallback;
        String v = value.trim();
        try {
            if (v.endsWith("ms")) return Duration.ofMillis(Long.parseLong(v.substring(0, v.length() - 2).trim()));
            if (v.endsWith("s"))  return Duration.ofSeconds(Long.parseLong(v.substring(0, v.length() - 1).trim()));
            if (v.endsWith("m"))  return Duration.ofMinutes(Long.parseLong(v.substring(0, v.length() - 1).trim()));
            if (v.endsWith("h"))  return Duration.ofHours(Long.parseLong(v.substring(0, v.length() - 1).trim()));
            return Duration.ofMillis(Long.parseLong(v));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static SslContext buildNettySslContext(SSLContextProperties properties) throws Exception {
        if (properties.isSkipHostnameVerification()) {
            return SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .build();
        }

        try (InputStream is = resolveInputStream(properties.getCertificatePath())) {
            CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
            X509Certificate certificate = (X509Certificate) certFactory.generateCertificate(is);

            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null);
            trustStore.setCertificateEntry(UUID.randomUUID().toString(), certificate);

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);

            return SslContextBuilder.forClient()
                    .trustManager(tmf)
                    .build();
        }
    }

    private static InputStream resolveInputStream(String path) throws FileNotFoundException {
        InputStream is = WebClientFactory.class.getClassLoader().getResourceAsStream(path);
        if (is != null) return is;
        return new FileInputStream(path);
    }
}

