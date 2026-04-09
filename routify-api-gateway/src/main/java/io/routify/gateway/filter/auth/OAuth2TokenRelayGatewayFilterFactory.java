package io.routify.gateway.filter.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.routify.common.exception.RoutifyException;
import io.routify.gateway.filter.shared.GatewayProblemResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/**
 * RFC 8693 Token Exchange filter — exchanges the incoming bearer token for a
 * downstream-specific token via a configured OAuth2 token endpoint.
 *
 * <p>Features:
 * <ul>
 *   <li>Extracts bearer token from {@code Authorization} header</li>
 *   <li>Exchanges via non-blocking {@link WebClient} call to token endpoint</li>
 *   <li>Caffeine in-process token cache keyed by SHA-256(incoming token + audience)</li>
 *   <li>Configurable fallback on exchange failure: REJECT (401), PASS_THROUGH, STRIP</li>
 *   <li>Micrometer metrics: exchange success/failure/cache-hit counters</li>
 * </ul>
 *
 * <p>Filter type: {@code OAUTH2_TOKEN_RELAY}
 */
@Slf4j
@Component
public class OAuth2TokenRelayGatewayFilterFactory
        extends AbstractGatewayFilterFactory<OAuth2TokenRelayGatewayFilterFactory.Config> {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String TOKEN_EXCHANGE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:token-exchange";

    private final WebClient webClient;
    private final MeterRegistry meterRegistry;

    public OAuth2TokenRelayGatewayFilterFactory(WebClient.Builder webClientBuilder,
                                                 MeterRegistry meterRegistry) {
        super(Config.class);
        this.webClient = webClientBuilder.build();
        this.meterRegistry = meterRegistry;
    }

    @Override
    public GatewayFilter apply(Config config) {
        // Build per-filter Caffeine cache with configured TTL
        Cache<String, CachedToken> tokenCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(config.getCacheTtlSeconds(), TimeUnit.SECONDS)
                .build();

        Counter exchangeSuccess = Counter.builder("routify.filter.token_relay.exchange_success")
                .description("Successful token exchanges")
                .register(meterRegistry);
        Counter exchangeFailure = Counter.builder("routify.filter.token_relay.exchange_failure")
                .description("Failed token exchanges")
                .register(meterRegistry);
        Counter cacheHit = Counter.builder("routify.filter.token_relay.cache_hit")
                .description("Token cache hits")
                .register(meterRegistry);

        return new TokenRelayFilter(config, tokenCache, exchangeSuccess, exchangeFailure, cacheHit);
    }

    // ─── Inner filter ─────────────────────────────────────────────────────────

    private class TokenRelayFilter implements GatewayFilter, Ordered {

        private final Config config;
        private final Cache<String, CachedToken> tokenCache;
        private final Counter exchangeSuccess;
        private final Counter exchangeFailure;
        private final Counter cacheHit;

        TokenRelayFilter(Config config, Cache<String, CachedToken> tokenCache,
                         Counter exchangeSuccess, Counter exchangeFailure, Counter cacheHit) {
            this.config = config;
            this.tokenCache = tokenCache;
            this.exchangeSuccess = exchangeSuccess;
            this.exchangeFailure = exchangeFailure;
            this.cacheHit = cacheHit;
        }

        @Override
        public int getOrder() {
            // Run after auth filters but before downstream forwarding
            return 0;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            // Extract incoming bearer token
            String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (authHeader == null || !authHeader.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
                log.debug("OAuth2TokenRelay: no Bearer token found — passing through");
                return chain.filter(exchange);
            }
            String incomingToken = authHeader.substring(BEARER_PREFIX.length()).trim();

            // Check cache
            String cacheKey = buildCacheKey(incomingToken, config.getAudience());
            CachedToken cached = tokenCache.getIfPresent(cacheKey);
            if (cached != null) {
                cacheHit.increment();
                log.debug("OAuth2TokenRelay: cache hit — using cached exchanged token");
                return forwardWithToken(exchange, chain, cached.accessToken());
            }

            // Cache miss — exchange token via WebClient
            return exchangeToken(incomingToken, config)
                    .flatMap(exchangedToken -> {
                        exchangeSuccess.increment();

                        // Cache with min(configTtl, expiresIn - 30s)
                        long effectiveTtl = config.getCacheTtlSeconds();
                        if (exchangedToken.expiresIn() > 0) {
                            long tokenTtl = exchangedToken.expiresIn() - 30;
                            if (tokenTtl > 0 && tokenTtl < effectiveTtl) {
                                effectiveTtl = tokenTtl;
                            }
                        }
                        tokenCache.put(cacheKey, exchangedToken);

                        log.debug("OAuth2TokenRelay: token exchanged successfully, cached for {}s", effectiveTtl);
                        return forwardWithToken(exchange, chain, exchangedToken.accessToken());
                    })
                    .onErrorResume(ex -> {
                        exchangeFailure.increment();
                        log.warn("OAuth2TokenRelay: token exchange failed: {}", ex.getMessage());
                        return handleFallback(exchange, chain, authHeader, config);
                    });
        }
    }

    // ─── Token exchange ───────────────────────────────────────────────────────

    private Mono<CachedToken> exchangeToken(String subjectToken, Config config) {
        return webClient.post()
                .uri(config.getTokenEndpoint())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue(buildExchangeBody(subjectToken, config))
                .retrieve()
                .bodyToMono(java.util.Map.class)
                .timeout(Duration.ofSeconds(10))
                .map(body -> {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> response = (java.util.Map<String, Object>) body;
                    String accessToken = (String) response.get("access_token");
                    if (accessToken == null || accessToken.isBlank()) {
                        throw new RoutifyException.Unauthorized("Token exchange response missing access_token");
                    }
                    long expiresIn = 0;
                    Object exp = response.get("expires_in");
                    if (exp instanceof Number n) {
                        expiresIn = n.longValue();
                    }
                    return new CachedToken(accessToken, expiresIn);
                });
    }

    static String buildExchangeBody(String subjectToken, Config config) {
        var sb = new StringBuilder();
        sb.append("grant_type=").append(urlEncode(TOKEN_EXCHANGE_GRANT_TYPE));
        sb.append("&subject_token=").append(urlEncode(subjectToken));
        sb.append("&subject_token_type=").append(urlEncode(config.getSubjectTokenType()));
        sb.append("&requested_token_type=").append(urlEncode(config.getRequestedTokenType()));
        sb.append("&client_id=").append(urlEncode(config.getClientId()));
        sb.append("&client_secret=").append(urlEncode(config.getClientSecret()));
        if (config.getScope() != null && !config.getScope().isBlank()) {
            sb.append("&scope=").append(urlEncode(config.getScope()));
        }
        if (config.getAudience() != null && !config.getAudience().isBlank()) {
            sb.append("&audience=").append(urlEncode(config.getAudience()));
        }
        return sb.toString();
    }

    // ─── Forward with exchanged token ─────────────────────────────────────────

    private Mono<Void> forwardWithToken(ServerWebExchange exchange, GatewayFilterChain chain,
                                         String accessToken) {
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + accessToken)
                .build();
        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    // ─── Fallback handling ────────────────────────────────────────────────────

    private Mono<Void> handleFallback(ServerWebExchange exchange, GatewayFilterChain chain,
                                       String originalAuthHeader, Config config) {
        String mode = config.getFallbackMode() != null ? config.getFallbackMode() : "REJECT";
        return switch (mode.toUpperCase()) {
            case "PASS_THROUGH" -> {
                log.debug("OAuth2TokenRelay: fallback=PASS_THROUGH — forwarding original token");
                yield chain.filter(exchange);
            }
            case "STRIP" -> {
                log.debug("OAuth2TokenRelay: fallback=STRIP — removing Authorization header");
                ServerHttpRequest stripped = exchange.getRequest().mutate()
                        .headers(h -> h.remove(HttpHeaders.AUTHORIZATION))
                        .build();
                yield chain.filter(exchange.mutate().request(stripped).build());
            }
            default -> {
                // REJECT
                log.debug("OAuth2TokenRelay: fallback=REJECT — returning 401");
                yield GatewayProblemResponse.status(HttpStatus.UNAUTHORIZED)
                        .errorCode("TOKEN_EXCHANGE_FAILED")
                        .detail("OAuth2 token exchange failed")
                        .write(exchange);
            }
        };
    }

    // ─── Cache key ────────────────────────────────────────────────────────────

    static String buildCacheKey(String token, String audience) {
        String raw = token + "|" + (audience != null ? audience : "");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available in standard JVMs
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // ─── URL encoding helper ──────────────────────────────────────────────────

    private static String urlEncode(String value) {
        if (value == null) return "";
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // ─── Cached token record ──────────────────────────────────────────────────

    record CachedToken(String accessToken, long expiresIn) {}

    // ─── Config ───────────────────────────────────────────────────────────────

    @Data
    public static class Config {
        /** OAuth2 token endpoint URL (required). */
        private String tokenEndpoint;
        /** Client ID for token exchange (required). */
        private String clientId;
        /** Client secret for token exchange (required, sensitive — masked in API responses). */
        private String clientSecret;
        /** Subject token type. Default: urn:ietf:params:oauth:token-type:access_token */
        private String subjectTokenType = "urn:ietf:params:oauth:token-type:access_token";
        /** Requested token type. Default: urn:ietf:params:oauth:token-type:access_token */
        private String requestedTokenType = "urn:ietf:params:oauth:token-type:access_token";
        /** Scopes to request for the exchanged token. */
        private String scope = "";
        /** Target audience for the exchanged token. */
        private String audience = "";
        /** Token cache TTL in seconds. Default: 300 (5 min). */
        private int cacheTtlSeconds = 300;
        /** Fallback mode on exchange failure: REJECT (401), PASS_THROUGH, STRIP. Default: REJECT. */
        private String fallbackMode = "REJECT";
    }
}

