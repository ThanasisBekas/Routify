package io.routify.gateway.filter;

import io.routify.common.web.RoutifyHeaders;
import io.routify.gateway.filter.shared.GatewayProblemResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Inbound filter — validates the client's {@code Authorization: Basic} header
 * against the {@code username}/{@code password} configured on the route.
 *
 * <p>Use this for filter type {@code AUTH_BASIC}: protect a route so only callers
 * that present the correct Basic credentials are forwarded to the upstream.
 *
 * <p>On success the filter injects {@code X-Auth-User-Id} and
 * {@code X-Auth-Type: BASIC} headers for downstream services.
 *
 * @see DownstreamBasicAuthGatewayFilterFactory for injecting Basic credentials outbound
 */
@Component
@Slf4j
public class BasicAuthGatewayFilterFactory
        extends AbstractGatewayFilterFactory<BasicAuthGatewayFilterFactory.Config> {

    public BasicAuthGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            if (config.getUsername() == null || config.getUsername().isBlank()
                    || config.getPassword() == null || config.getPassword().isBlank()) {
                log.error("AUTH_BASIC filter is misconfigured: username/password not set on route");
                return unauthorized(exchange, "GATEWAY_MISCONFIGURATION",
                        "Basic auth credentials are not configured for this route");
            }

            String authHeader = exchange.getRequest().getHeaders()
                    .getFirst(HttpHeaders.AUTHORIZATION);

            if (authHeader == null || !authHeader.startsWith("Basic ")) {
                log.debug("AUTH_BASIC: missing or non-Basic Authorization header");
                return unauthorized(exchange, "MISSING_CREDENTIALS",
                        "Authorization header with Basic scheme is required");
            }

            String[] credentials = decodeCredentials(authHeader);
            if (credentials == null) {
                log.debug("AUTH_BASIC: malformed Base64 credentials");
                return unauthorized(exchange, "INVALID_CREDENTIALS",
                        "Basic auth credentials are malformed");
            }

            String incomingUser = credentials[0];
            String incomingPass = credentials[1];

            if (!config.getUsername().equals(incomingUser)
                    || !config.getPassword().equals(incomingPass)) {
                log.debug("AUTH_BASIC: invalid credentials for user '{}'", incomingUser);
                return unauthorized(exchange, "INVALID_CREDENTIALS",
                        "Invalid username or password");
            }

            log.debug("AUTH_BASIC: authenticated user '{}'", incomingUser);

            ServerHttpRequest mutated = exchange.getRequest().mutate()
                    .header(RoutifyHeaders.AUTH_USER_ID, incomingUser)
                    .header(RoutifyHeaders.AUTH_TYPE, "BASIC")
                    .build();

            return chain.filter(exchange.mutate().request(mutated).build());
        };
    }

    /**
     * Decodes a {@code Basic <base64>} header.
     *
     * @return {@code [username, password]} or {@code null} if decoding fails
     */
    private String[] decodeCredentials(String authHeader) {
        try {
            String base64 = authHeader.substring("Basic ".length()).trim();
            String decoded = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            if (colon < 0) return null;
            return new String[]{decoded.substring(0, colon), decoded.substring(colon + 1)};
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String errorCode, String detail) {
        return GatewayProblemResponse.status(HttpStatus.UNAUTHORIZED)
                .errorCode(errorCode)
                .detail(detail)
                .header("WWW-Authenticate", "Basic realm=\"Routify\"")
                .write(exchange);
    }

    @Data
    public static class Config {
        private String username;
        private String password;
    }
}
