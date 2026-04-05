package io.routify.gateway.filter;

import io.routify.gateway.auth.properties.AuthProperties;
import io.routify.gateway.downstream.oauth2.Oauth2BearerTokenVerifier;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

import static io.routify.gateway.auth.util.AuthenticationUtils.extractBearerToken;
import static io.routify.gateway.auth.util.AuthenticationUtils.mapClaimsToHeaders;
import static java.util.Objects.isNull;

/**
 * Inbound auth filter — verifies the caller's Bearer token against a configured
 * OAuth2 introspection endpoint, maps claims to downstream headers, and strips
 * {@code Authorization} from the forwarded request.
 */
@Slf4j
@Component
public class OAuth2TokenIntrospectGatewayFilterFactory
        extends AbstractGatewayFilterFactory<OAuth2TokenIntrospectGatewayFilterFactory.Config>
        implements Ordered {

    private final AuthProperties authProperties;
    private final Oauth2BearerTokenVerifier bearerTokenVerifier;

    public OAuth2TokenIntrospectGatewayFilterFactory(Oauth2BearerTokenVerifier bearerTokenVerifier,
                                                      AuthProperties authProperties) {
        super(OAuth2TokenIntrospectGatewayFilterFactory.Config.class);
        this.authProperties = authProperties;
        this.bearerTokenVerifier = bearerTokenVerifier;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String provider = config.getProviderName();
            AuthProperties.Oauth2VerificationConfig verificationConfig =
                    authProperties.getOauth2Verification().get(provider);

            if (isNull(verificationConfig)) {
                log.error("Missing Oauth2 verification configuration for provider {}", provider);
                return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Missing Oauth2 verification configuration for provider %s".formatted(provider)));
            }

            String token = extractBearerToken(exchange.getRequest());
            if (isNull(token)) {
                return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Missing or invalid Bearer Authorization header"));
            }

            log.debug("Verifying Bearer Authorization token against provider {}", provider);

            return bearerTokenVerifier.verifyToken(provider, token)
                    .flatMap(response -> {
                        HttpStatus responseStatus = HttpStatus.valueOf(response.statusCode());
                        if (!HttpStatus.OK.equals(responseStatus)) {
                            return Mono.error(new ResponseStatusException(responseStatus, response.body()));
                        }

                        ServerHttpRequest request = exchange.getRequest()
                                .mutate()
                                .headers(httpHeaders -> httpHeaders.remove(HttpHeaders.AUTHORIZATION))
                                .headers(httpHeaders -> httpHeaders.setAll(
                                        mapClaimsToHeaders(config.getClaimsToHeaderMapping(),
                                                response.claims())))
                                .build();

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
        /** The name of the OAuth2 verification provider (from {@code auth.oauth2Verification.*}). */
        private String providerName;

        /** Mapping of claim names to downstream header names. */
        private Map<String, String> claimsToHeaderMapping = new HashMap<>();
    }
}

