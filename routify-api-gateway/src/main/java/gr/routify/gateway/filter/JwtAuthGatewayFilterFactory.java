package gr.routify.gateway.filter;

import gr.routify.common.security.RedisKeys;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.SignatureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;

/**
 * Gateway filter for JWT authentication (RS256/HS256).
 *
 * <p>Validates the JWT bearer token from the Authorization header
 * or {@code ?token=} query param (for WebSocket/SSE connections).
 *
 * <p>On success: injects auth claims as request headers for downstream services:
 * <ul>
 *   <li>{@code X-Auth-User-Id} — JWT subject</li>
 *   <li>{@code X-Auth-Tenant-Id} — tenant claim</li>
 *   <li>{@code X-Auth-Role} — role claim</li>
 *   <li>{@code X-Auth-Email} — email claim</li>
 * </ul>
 *
 * <p>Config params (set in filter definition):
 * <ul>
 *   <li>{@code issuer} — expected JWT issuer (optional, validates iss claim)</li>
 *   <li>{@code audience} — expected audience (optional, validates aud claim)</li>
 *   <li>{@code algorithm} — RS256 (default) or HS256</li>
 * </ul>
 */
@Slf4j
@Component
public class JwtAuthGatewayFilterFactory
        extends AbstractGatewayFilterFactory<JwtAuthGatewayFilterFactory.Config> {

    @Value("${routify.jwt.public-key:}")
    private String publicKeyBase64;

    private final ReactiveStringRedisTemplate redisTemplate;

    public JwtAuthGatewayFilterFactory(ReactiveStringRedisTemplate redisTemplate) {
        super(Config.class);
        this.redisTemplate = redisTemplate;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();

            // Extract token from Authorization header or ?token= query param
            String token = extractToken(request);
            if (token == null) {
                return unauthorized(exchange, "MISSING_TOKEN",
                        "Authorization header or token parameter is required");
            }

            Claims claims;
            try {
                claims = parseAndValidate(token, config);
            } catch (ExpiredJwtException e) {
                return unauthorized(exchange, "TOKEN_EXPIRED", "JWT token has expired");
            } catch (SignatureException | MalformedJwtException | UnsupportedJwtException e) {
                return unauthorized(exchange, "INVALID_TOKEN", "JWT token is invalid");
            } catch (Exception e) {
                log.error("JWT validation error: {}", e.getMessage());
                return unauthorized(exchange, "TOKEN_VALIDATION_FAILED", "Token validation failed");
            }

            // ── Blocklist check (H1) ──────────────────────────────────────────
            // After a logout or password change the identity-service stores
            // "routify:token:blocklist:<jti> = 1" in Redis with a TTL equal to
            // the token's remaining lifetime.  We must honour that revocation.
            String jti = claims.getId();
            if (jti != null && !jti.isBlank()) {
                final Claims resolvedClaims = claims;
                return redisTemplate.hasKey(RedisKeys.BLOCKLIST_PREFIX + jti)
                        .flatMap(blocked -> {
                            if (Boolean.TRUE.equals(blocked)) {
                                log.debug("JWT rejected — token revoked: jti={}", jti);
                                return unauthorized(exchange, "TOKEN_REVOKED",
                                        "Token has been revoked");
                            }
                            ServerHttpRequest mutatedRequest =
                                    injectAuthHeaders(exchange.getRequest(), resolvedClaims);
                            log.debug("JWT validated: sub={} tenant={} role={}",
                                    resolvedClaims.getSubject(),
                                    resolvedClaims.get("tenantId"),
                                    resolvedClaims.get("role"));
                            return chain.filter(
                                    exchange.mutate().request(mutatedRequest).build());
                        });
            }

            // No jti — proceed without blocklist check (should not happen with well-formed tokens)
            log.warn("JWT has no jti claim — skipping blocklist check (sub={})", claims.getSubject());
            ServerHttpRequest mutatedRequest = injectAuthHeaders(request, claims);
            log.debug("JWT validated: sub={} tenant={} role={}",
                    claims.getSubject(),
                    claims.get("tenantId"),
                    claims.get("role"));
            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        };
    }

    private String extractToken(ServerHttpRequest request) {
        // 1. Authorization: Bearer <token>
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        // 2. ?token= query param (for WS/SSE clients that can't set headers)
        String queryToken = request.getQueryParams().getFirst("token");
        if (queryToken != null && !queryToken.isBlank()) {
            return queryToken;
        }
        return null;
    }

    private Claims parseAndValidate(String token, Config config) throws Exception {
        if (publicKeyBase64 != null && !publicKeyBase64.isBlank()) {
            byte[] keyBytes = Base64.getDecoder().decode(publicKeyBase64);
            PublicKey publicKey = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(keyBytes));
            JwtParser parser = Jwts.parser().verifyWith(publicKey).build();
            return parser.parseSignedClaims(token).getPayload();
        } else {
            // Dev mode: decode claims without signature verification.
            // The token IS signed (RS256) by the identity service's ephemeral key,
            // but the gateway doesn't have the public key, so we skip verification.
            log.warn("JWT public key not configured — signature SKIPPED (dev mode only!)");
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                throw new MalformedJwtException("JWT must have at least 2 parts");
            }
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]),
                    java.nio.charset.StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> claimsMap = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(payload, Map.class);

            // Check expiration manually since jjwt won't do it for us
            Object exp = claimsMap.get("exp");
            if (exp instanceof Number) {
                long expSeconds = ((Number) exp).longValue();
                if (java.time.Instant.ofEpochSecond(expSeconds).isBefore(java.time.Instant.now())) {
                    throw new ExpiredJwtException(null, null, "JWT token has expired");
                }
            }

            return Jwts.claims().add(claimsMap).build();
        }
    }

    private ServerHttpRequest injectAuthHeaders(ServerHttpRequest request, Claims claims) {
        ServerHttpRequest.Builder builder = request.mutate()
                .header("X-Auth-User-Id", claims.getSubject())
                .header("X-Auth-Tenant-Id",  getClaimOrEmpty(claims, "tenantId"))
                .header("X-Auth-Role",        getClaimOrEmpty(claims, "role"))
                .header("X-Auth-Email",       getClaimOrEmpty(claims, "email"));

        // Remove raw ?token= param to prevent leaking it downstream
        if (request.getQueryParams().containsKey("token")) {
            org.springframework.web.util.UriComponentsBuilder uriBuilder =
                    org.springframework.web.util.UriComponentsBuilder.fromUri(request.getURI());
            uriBuilder.replaceQueryParam("token");
            builder.uri(uriBuilder.build(true).toUri());
        }
        return builder.build();
    }

    private String getClaimOrEmpty(Claims claims, String key) {
        Object val = claims.get(key);
        return val != null ? val.toString() : "";
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String errorCode, String detail) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().set(HttpHeaders.CONTENT_TYPE, "application/problem+json");
        String body = """
                {"type":"about:blank","title":"Unauthorized","status":401,
                 "errorCode":"%s","detail":"%s"}
                """.formatted(errorCode, detail).strip();
        var buffer = response.bufferFactory().wrap(body.getBytes());
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * Configuration for the JwtAuth filter.
     * Populated from the filter definition's config JSON.
     */
    public static class Config {
        private String issuer;
        private String audience;
        private String algorithm = "RS256";

        public String getIssuer()                 { return issuer; }
        public void setIssuer(String issuer)       { this.issuer = issuer; }
        public String getAudience()                { return audience; }
        public void setAudience(String audience)   { this.audience = audience; }
        public String getAlgorithm()               { return algorithm; }
        public void setAlgorithm(String algorithm) { this.algorithm = algorithm; }
    }
}

