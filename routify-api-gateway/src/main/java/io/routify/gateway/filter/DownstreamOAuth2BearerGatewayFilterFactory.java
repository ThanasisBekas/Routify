package io.routify.gateway.filter;

import io.routify.gateway.downstream.oauth2.Oauth2AccessTokenProvider;
import io.routify.gateway.filter.shared.GatewayProblemResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Outbound filter — acquires an OAuth2 client-credentials token and injects it
 * as a downstream {@code Bearer} header.
 *
 * <h3>Two configuration paths</h3>
 * <ol>
 *   <li><b>Named provider (legacy):</b> Set {@code oauth2ProviderName} to reference a
 *       static {@code auth.oauth2.<name>} YAML config entry.</li>
 *   <li><b>Direct config (P-25):</b> Set {@code tokenUri}, {@code clientId},
 *       {@code clientSecret}, and optionally {@code scope} directly — typically
 *       injected by {@code GatewayConfigRefResolver} from a
 *       {@code DOWNSTREAM_OAUTH2_PROVIDER} gateway config ref. When these fields
 *       are present, the named provider lookup is bypassed.</li>
 * </ol>
 *
 * <p>Set {@code forwardCallerAuth=true} to forward the caller's own
 * {@code Authorization} header to the token endpoint instead (uncached).
 */
@Component
@Slf4j
public class DownstreamOAuth2BearerGatewayFilterFactory
        extends AbstractGatewayFilterFactory<DownstreamOAuth2BearerGatewayFilterFactory.Config>
        implements Ordered {

    private static final String BEARER_PREFIX = "Bearer ";
    private final Oauth2AccessTokenProvider accessTokenProvider;

    public DownstreamOAuth2BearerGatewayFilterFactory(Oauth2AccessTokenProvider accessTokenProvider) {
        super(Config.class);
        this.accessTokenProvider = accessTokenProvider;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {

            // ── Direct config path (P-25): tokenUri + clientId + clientSecret ──
            if (config.hasDirectOAuth2Config()) {
                log.debug("Using direct OAuth2 config (tokenUri={})", config.getTokenUri());

                Mono<String> tokenMono = config.isForwardCallerAuth()
                        ? accessTokenProvider.accessTokenDirectForwardedAuth(
                                config.getTokenUri(), exchange.getRequest())
                        : accessTokenProvider.accessTokenDirectClientCredentials(
                                config.getTokenUri(), config.getClientId(),
                                config.getClientSecret(), config.getScope(),
                                config.isIncludeBasicClientAuthorization());

                return tokenMono.flatMap(accessToken -> injectBearer(exchange, chain, accessToken,
                        "direct:" + config.getTokenUri(), config.isForwardCallerAuth()));
            }

            // ── Named provider path (legacy) ──────────────────────────────────
            String oauth2ProviderName = config.getOauth2ProviderName();

            if (oauth2ProviderName == null || oauth2ProviderName.isEmpty()) {
                log.error("Missing required oauth2 provider name or direct config fields");
                return GatewayProblemResponse.status(HttpStatus.UNAUTHORIZED)
                        .errorCode("MISSING_OAUTH2_PROVIDER")
                        .detail("Missing required oauth2 provider name '%s'", oauth2ProviderName)
                        .write(exchange);
            }

            Mono<String> tokenMono = config.isForwardCallerAuth()
                    ? accessTokenProvider.accessTokenForwardedAuth(oauth2ProviderName,
                            exchange.getRequest())
                    : accessTokenProvider.accessTokenClientCredentials(oauth2ProviderName);

            return tokenMono.flatMap(accessToken -> injectBearer(exchange, chain, accessToken,
                    oauth2ProviderName, config.isForwardCallerAuth()));
        };
    }

    private Mono<Void> injectBearer(org.springframework.web.server.ServerWebExchange exchange,
                                     org.springframework.cloud.gateway.filter.GatewayFilterChain chain,
                                     String accessToken, String providerLabel, boolean forwardCallerAuth) {
        log.debug("Setting Bearer Authorization header to downstream request for oauth2 provider {} (forwardCallerAuth={})",
                providerLabel, forwardCallerAuth);

        ServerHttpRequest request = exchange.getRequest().mutate()
                .header("Authorization", BEARER_PREFIX + accessToken).build();

        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return 1;
    }

    @Data
    public static class Config {
        /**
         * Named provider from {@code auth.oauth2.<name>} YAML config.
         * Used when direct OAuth2 fields are not set.
         */
        private String oauth2ProviderName;

        /**
         * When {@code true}, forwards the caller's {@code Authorization} header uncached.
         * Defaults to {@code false} (cached gateway credentials).
         */
        private boolean forwardCallerAuth = false;

        // ─── Direct OAuth2 config fields (P-25 — DOWNSTREAM_OAUTH2_PROVIDER ref) ──

        /** Token endpoint URI (e.g. {@code https://auth.example.com/oauth/token}). */
        private String tokenUri;

        /** OAuth2 client ID for client-credentials grant. */
        private String clientId;

        /** OAuth2 client secret for client-credentials grant. */
        private String clientSecret;

        /** Space-separated scopes (optional). */
        private String scope;

        /** When true, sends clientId:clientSecret as Basic Authorization header. */
        private boolean includeBasicClientAuthorization = false;

        /**
         * Returns {@code true} if direct OAuth2 config fields are present,
         * indicating the named-provider path should be bypassed.
         */
        public boolean hasDirectOAuth2Config() {
            return tokenUri != null && !tokenUri.isBlank()
                    && clientId != null && !clientId.isBlank()
                    && clientSecret != null && !clientSecret.isBlank();
        }
    }
}

