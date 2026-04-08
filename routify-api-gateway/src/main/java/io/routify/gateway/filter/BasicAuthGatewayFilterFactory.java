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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
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

    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder(12);

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
                    || !matchesPassword(incomingPass, config.getPassword())) {
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
     * Matches the incoming raw password against the stored credential.
     *
     * <p>If the stored value looks like a BCrypt hash ({@code $2a$}, {@code $2b$},
     * or {@code $2y$} prefix), the incoming password is verified using
     * {@link BCryptPasswordEncoder#matches}. Otherwise, a plain-text {@code equals}
     * comparison is used as a fallback for legacy configs that have not yet been
     * re-saved through the admin API.
     *
     * @param rawPassword    the plain-text password sent by the client
     * @param storedPassword the stored credential (BCrypt hash or legacy plain text)
     * @return {@code true} if the password matches
     */
    private boolean matchesPassword(String rawPassword, String storedPassword) {
        if (storedPassword != null && storedPassword.startsWith("$2")) {
            return BCRYPT.matches(rawPassword, storedPassword);
        }
        // Fallback: plain-text comparison for legacy configs not yet re-hashed
        log.warn("AUTH_BASIC: password is stored in plain text — re-save the auth provider to hash it");
        return storedPassword != null && storedPassword.equals(rawPassword);
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
