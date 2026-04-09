package io.routify.gateway.filter;

import io.routify.common.crypto.FieldEncryptionService;
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
 * <p>Supports three stored password formats:
 * <ol>
 *   <li><strong>AES-encrypted</strong> ({@code {enc}} prefix) — decrypts to plaintext, then compares.
 *       This is the current default for newly saved auth providers.</li>
 *   <li><strong>BCrypt hash</strong> ({@code $2} prefix) — legacy format; uses
 *       {@link BCryptPasswordEncoder#matches}. Re-saving the provider via the admin UI
 *       migrates it to AES encryption.</li>
 *   <li><strong>Plain text</strong> — fallback for very old configs; direct {@code equals}.</li>
 * </ol>
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

    /** Legacy BCrypt encoder — kept for backward compatibility with $2-prefixed hashes. */
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder(12);

    private final FieldEncryptionService fieldEncryptionService;

    public BasicAuthGatewayFilterFactory(FieldEncryptionService fieldEncryptionService) {
        super(Config.class);
        this.fieldEncryptionService = fieldEncryptionService;
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
     * <p>Supports three formats:
     * <ol>
     *   <li>{@code {enc}...} — AES-256-GCM encrypted: decrypt and compare plaintext</li>
     *   <li>{@code $2a$/$2b$/$2y$...} — legacy BCrypt hash: use BCryptPasswordEncoder#matches</li>
     *   <li>anything else — plain-text fallback for very old configs</li>
     * </ol>
     */
    private boolean matchesPassword(String rawPassword, String storedPassword) {
        if (storedPassword == null) return false;

        if (fieldEncryptionService.isEncrypted(storedPassword)) {
            String decrypted = fieldEncryptionService.decrypt(storedPassword);
            return decrypted != null && decrypted.equals(rawPassword);
        }

        if (storedPassword.startsWith("$2")) {
            // Legacy BCrypt hash — kept for backward compatibility
            log.warn("AUTH_BASIC: password is stored as BCrypt hash — re-save the auth provider to migrate to AES encryption");
            return BCRYPT.matches(rawPassword, storedPassword);
        }

        // Fallback: plain-text comparison for legacy configs
        log.warn("AUTH_BASIC: password is stored in plain text — re-save the auth provider to encrypt it");
        return storedPassword.equals(rawPassword);
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
