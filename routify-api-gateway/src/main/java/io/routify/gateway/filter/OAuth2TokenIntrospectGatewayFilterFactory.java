package io.routify.gateway.filter;

import io.routify.gateway.auth.properties.AuthProperties;
import io.routify.gateway.auth.properties.ParameterStyle;
import io.routify.gateway.downstream.oauth2.Oauth2BearerTokenVerifier;
import io.routify.gateway.filter.shared.GatewayProblemResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
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
 *
 * <h3>Two operational modes (P-04):</h3>
 * <ol>
 *   <li><strong>Direct config (gateway config ref)</strong>: When {@code introspectUri},
 *       {@code clientId}, and {@code clientSecret} are present in the filter config
 *       (populated by {@code GatewayConfigRefResolver.resolveAuthProvider()}), the filter
 *       calls the introspection endpoint directly without going through
 *       {@link AuthProperties}. This is the recommended path for dashboard-managed
 *       OAuth2 providers.</li>
 *   <li><strong>Provider name (legacy YAML)</strong>: When only {@code providerName} is
 *       set, the filter looks up the configuration in
 *       {@code AuthProperties.oauth2Verification} — the static YAML-driven path.
 *       Fully backward compatible.</li>
 * </ol>
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
            String token = extractBearerToken(exchange.getRequest());
            if (isNull(token)) {
                return GatewayProblemResponse.status(HttpStatus.BAD_REQUEST)
                        .errorCode("MISSING_BEARER_TOKEN")
                        .detail("Missing or invalid Bearer Authorization header")
                        .write(exchange);
            }

            // ── Path 1: Direct config from gateway config ref (P-04) ──────────
            if (hasDirectConfig(config)) {
                log.debug("Verifying Bearer token via dynamic gateway config (introspectUri={})",
                        config.getIntrospectUri());

                ParameterStyle style = parseParameterStyle(config.getParameterStyle());

                return bearerTokenVerifier.verifyToken(
                                config.getIntrospectUri(),
                                config.getClientId(),
                                config.getClientSecret(),
                                style,
                                config.getParameterName(),
                                config.getContentType(),
                                config.isIncludeBasicClientAuthorization(),
                                token)
                        .flatMap(response -> handleVerificationResponse(exchange, chain, config, response,
                                "dynamic:" + config.getIntrospectUri()));
            }

            // ── Path 2: Legacy provider name → AuthProperties YAML ────────────
            String provider = config.getProviderName();
            if (isNull(provider) || provider.isBlank()) {
                log.error("AUTH_OAUTH2 filter misconfigured: neither introspectUri nor providerName is set");
                return GatewayProblemResponse.status(HttpStatus.UNAUTHORIZED)
                        .errorCode("OAUTH2_MISCONFIGURED")
                        .detail("OAuth2 filter has no introspectUri and no providerName configured")
                        .write(exchange);
            }

            AuthProperties.Oauth2VerificationConfig verificationConfig =
                    authProperties.getOauth2Verification().get(provider);
            if (isNull(verificationConfig)) {
                log.error("Missing Oauth2 verification configuration for provider {}", provider);
                return GatewayProblemResponse.status(HttpStatus.UNAUTHORIZED)
                        .errorCode("OAUTH2_MISCONFIGURED")
                        .detail("Missing Oauth2 verification configuration for provider %s", provider)
                        .write(exchange);
            }

            log.debug("Verifying Bearer Authorization token against static provider {}", provider);

            return bearerTokenVerifier.verifyToken(provider, token)
                    .flatMap(response -> handleVerificationResponse(exchange, chain, config, response, provider));
        };
    }

    /**
     * Returns {@code true} when the direct-config fields are sufficiently populated
     * to bypass the legacy {@code providerName} → {@link AuthProperties} lookup.
     */
    private boolean hasDirectConfig(Config config) {
        return config.getIntrospectUri() != null && !config.getIntrospectUri().isBlank()
                && config.getClientId() != null && !config.getClientId().isBlank()
                && config.getClientSecret() != null && !config.getClientSecret().isBlank();
    }

    private Mono<Void> handleVerificationResponse(
            org.springframework.web.server.ServerWebExchange exchange,
            org.springframework.cloud.gateway.filter.GatewayFilterChain chain,
            Config config,
            io.routify.gateway.downstream.oauth2.TokenVerificationResponse response,
            String providerLabel) {

        HttpStatus responseStatus = HttpStatus.valueOf(response.statusCode());
        if (!HttpStatus.OK.equals(responseStatus)) {
            return GatewayProblemResponse.status(responseStatus)
                    .errorCode("OAUTH2_INTROSPECT_FAILED")
                    .detail(response.body())
                    .write(exchange);
        }

        ServerHttpRequest request = exchange.getRequest()
                .mutate()
                .headers(httpHeaders -> httpHeaders.remove(HttpHeaders.AUTHORIZATION))
                .headers(httpHeaders -> httpHeaders.setAll(
                        mapClaimsToHeaders(config.getClaimsToHeaderMapping(),
                                response.claims())))
                .build();

        return chain.filter(exchange.mutate().request(request).build());
    }

    private static ParameterStyle parseParameterStyle(String style) {
        if (style == null || style.isBlank()) return ParameterStyle.BODY;
        try {
            return ParameterStyle.valueOf(style.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown parameterStyle '{}' — defaulting to BODY", style);
            return ParameterStyle.BODY;
        }
    }

    @Override
    public int getOrder() {
        return 1;
    }

    /**
     * Filter configuration supporting both the legacy {@code providerName} path
     * and the new direct-config path via {@code gatewayConfigRef} resolution.
     *
     * <p>When {@code introspectUri}, {@code clientId}, and {@code clientSecret}
     * are all present, the direct path is used. Otherwise, {@code providerName}
     * is looked up in {@link AuthProperties#getOauth2Verification()}.
     */
    @Data
    public static class Config {
        // ── Legacy path: provider name → AuthProperties YAML ──────────────────
        /** The name of the OAuth2 verification provider (from {@code auth.oauth2Verification.*}). */
        private String providerName;

        // ── Direct config path (P-04): populated by gatewayConfigRef resolution ─
        /** URI of the OAuth2 introspection endpoint. */
        private String introspectUri;

        /** Client ID for the introspection call. */
        private String clientId;

        /** Client secret for the introspection call. */
        private String clientSecret;

        /** How the token is passed to the introspection endpoint. Default: BODY. */
        private String parameterStyle;

        /** Form field / query param / header name for the token. Default: token. */
        private String parameterName;

        /** Optional Content-Type for the introspection request. */
        private String contentType;

        /** Whether to include Basic auth (clientId:clientSecret) in the Authorization header. Default: true. */
        private boolean includeBasicClientAuthorization = true;

        // ── Shared config ─────────────────────────────────────────────────────
        /** Mapping of claim names to downstream header names. */
        private Map<String, String> claimsToHeaderMapping = new HashMap<>();
    }
}

