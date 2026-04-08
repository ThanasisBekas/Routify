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
            String oauth2ProviderName = config.getOauth2ProviderName();

            if (oauth2ProviderName == null || oauth2ProviderName.isEmpty()) {
                log.error("Missing required oauth2 provider name {}", oauth2ProviderName);
                return GatewayProblemResponse.status(HttpStatus.UNAUTHORIZED)
                        .errorCode("MISSING_OAUTH2_PROVIDER")
                        .detail("Missing required oauth2 provider name '%s'", oauth2ProviderName)
                        .write(exchange);
            }

            Mono<String> tokenMono = config.isForwardCallerAuth()
                    ? accessTokenProvider.accessTokenForwardedAuth(oauth2ProviderName,
                            exchange.getRequest())
                    : accessTokenProvider.accessTokenClientCredentials(oauth2ProviderName);

            return tokenMono.flatMap(accessToken -> {
                log.debug("Setting Bearer Authorization header to downstream request for oauth2 provider {} (forwardCallerAuth={})",
                        oauth2ProviderName, config.isForwardCallerAuth());

                ServerHttpRequest request = exchange.getRequest().mutate()
                        .header("Authorization", BEARER_PREFIX + accessToken).build();

                return chain.filter(exchange.mutate().request(request).build());
            });
        };
    }

    @Override
    public int getOrder() {
        return 1;
    }

    @Data
    public static class Config {
        private String oauth2ProviderName;

        /**
         * When {@code true}, forwards the caller's {@code Authorization} header uncached.
         * Defaults to {@code false} (cached gateway credentials).
         */
        private boolean forwardCallerAuth = false;
    }
}

