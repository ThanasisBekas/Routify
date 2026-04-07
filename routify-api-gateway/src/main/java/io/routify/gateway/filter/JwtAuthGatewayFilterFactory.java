package io.routify.gateway.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.routify.common.security.RedisKeys;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.SignatureException;
import io.routify.gateway.filter.shared.GatewayProblemResponse;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gateway filter for JWT authentication (RS256 only).
 *
 * <p>Validates the JWT bearer token from the Authorization header
 * or {@code ?token=} query param (for WebSocket/SSE connections).
 *
 * <p><b>Security hardening (GF-04):</b>
 * <ul>
 *   <li>No unsigned JWT decode path — fail-closed if neither public key nor JWKS URI is configured</li>
 *   <li>JWKS URI support with Caffeine-cached key resolution (key rotation without restart)</li>
 *   <li>Issuer and audience claim validation when configured</li>
 *   <li>RS256-only — HS256 no longer supported</li>
 *   <li>{@code require-jti=true} by default — tokens without a {@code jti} claim are rejected</li>
 * </ul>
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
 *   <li>{@code requireJti} — override global require-jti setting per filter (default: uses global)</li>
 * </ul>
 */
@Slf4j
@Component
public class JwtAuthGatewayFilterFactory
        extends AbstractGatewayFilterFactory<JwtAuthGatewayFilterFactory.Config> {

    @Value("${routify.jwt.public-key:}")
    private String publicKeyBase64;

    @Value("${routify.jwt.jwks-uri:}")
    private String jwksUri;

    @Value("${routify.jwt.jwks-cache-minutes:5}")
    private int jwksCacheMinutes;

    @Value("${routify.jwt.require-jti:true}")
    private boolean globalRequireJti;

    private final ReactiveStringRedisTemplate redisTemplate;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    /** Static public key parsed from Base64 config at startup. Null if not configured. */
    private volatile PublicKey staticPublicKey;

    /** Whether the filter is misconfigured (neither public key nor JWKS URI). */
    private volatile boolean misconfigured;

    /**
     * Caffeine cache for JWKS keys, keyed by {@code kid} (Key ID).
     * TTL matches {@code routify.jwt.jwks-cache-minutes}.
     */
    private Cache<String, PublicKey> jwksKeyCache;

    /** Guards concurrent JWKS fetches — only one in-flight per kid. */
    private final Map<String, Mono<PublicKey>> inflightJwksFetches = new ConcurrentHashMap<>();

    /** Sentinel key used when JWTs don't have a kid and JWKS has only one key. */
    private static final String DEFAULT_KID = "__default__";

    public JwtAuthGatewayFilterFactory(ReactiveStringRedisTemplate redisTemplate,
                                       WebClient.Builder webClientBuilder,
                                       ObjectMapper objectMapper) {
        super(Config.class);
        this.redisTemplate = redisTemplate;
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void validateKeySource() {
        // Parse static public key if configured
        if (publicKeyBase64 != null && !publicKeyBase64.isBlank()) {
            try {
                byte[] keyBytes = Base64.getDecoder().decode(publicKeyBase64);
                staticPublicKey = KeyFactory.getInstance("RSA")
                        .generatePublic(new X509EncodedKeySpec(keyBytes));
                log.info("JWT static public key loaded successfully (RS256)");
            } catch (Exception e) {
                log.error("Failed to parse JWT static public key: {}", e.getMessage());
                staticPublicKey = null;
            }
        }

        // Initialise JWKS key cache
        jwksKeyCache = Caffeine.newBuilder()
                .maximumSize(50)
                .expireAfterWrite(Duration.ofMinutes(Math.max(1, jwksCacheMinutes)))
                .build();

        // Validate: at least one key source must be configured
        boolean hasStaticKey = staticPublicKey != null;
        boolean hasJwksUri = jwksUri != null && !jwksUri.isBlank();

        if (!hasStaticKey && !hasJwksUri) {
            log.error("╔════════════════════════════════════════════════════════════════╗");
            log.error("║  JWT MISCONFIGURED — neither public key nor JWKS URI set!     ║");
            log.error("║                                                                ║");
            log.error("║  All JWT authentication requests will be REJECTED (500).       ║");
            log.error("║                                                                ║");
            log.error("║  Fix: set one of:                                              ║");
            log.error("║    • routify.jwt.public-key  (Base64-encoded RSA public key)   ║");
            log.error("║    • routify.jwt.jwks-uri    (JWKS endpoint URL)               ║");
            log.error("╚════════════════════════════════════════════════════════════════╝");
            misconfigured = true;
        } else {
            misconfigured = false;
            if (hasJwksUri) {
                log.info("JWT JWKS URI configured: {} (cache TTL: {} min)", jwksUri, jwksCacheMinutes);
            }
            if (hasStaticKey && hasJwksUri) {
                log.info("Both static key and JWKS URI configured — JWKS takes precedence, static key is fallback");
            }
        }
    }

    @Override
    public GatewayFilter apply(Config config) {
        // Warn if HS256 is configured (no longer supported)
        if ("HS256".equalsIgnoreCase(config.getAlgorithm())) {
            log.warn("HS256 is no longer supported — Routify uses RS256 exclusively. "
                    + "Ignoring algorithm config and using RS256.");
        }

        return (exchange, chain) -> {
            // ── Fail-closed: reject all requests if misconfigured ────────────
            if (misconfigured) {
                return GatewayProblemResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .errorCode("SERVER_MISCONFIGURED")
                        .detail("JWT authentication is not configured — "
                                + "neither public key nor JWKS URI is set. "
                                + "Contact the platform administrator.")
                        .write(exchange);
            }

            ServerHttpRequest request = exchange.getRequest();

            // Extract token from Authorization header or ?token= query param
            String token = extractToken(request);
            if (token == null) {
                return unauthorized(exchange, "MISSING_TOKEN",
                        "Authorization header or token parameter is required");
            }

            // Resolve the signing key (potentially async for JWKS) then validate
            return resolveSigningKey(token)
                    .flatMap(key -> {
                        Claims claims;
                        try {
                            claims = parseAndValidate(token, key);
                        } catch (ExpiredJwtException e) {
                            return unauthorized(exchange, "TOKEN_EXPIRED", "JWT token has expired");
                        } catch (SignatureException | MalformedJwtException | UnsupportedJwtException e) {
                            return unauthorized(exchange, "INVALID_TOKEN", "JWT token is invalid");
                        } catch (Exception e) {
                            log.error("JWT validation error: {}", e.getMessage());
                            return unauthorized(exchange, "TOKEN_VALIDATION_FAILED",
                                    "Token validation failed");
                        }

                        // ── Issuer validation ────────────────────────────────
                        if (config.getIssuer() != null && !config.getIssuer().isBlank()) {
                            String tokenIssuer = claims.getIssuer();
                            if (tokenIssuer == null || !tokenIssuer.equals(config.getIssuer())) {
                                log.debug("JWT rejected — issuer mismatch: expected={}, got={}",
                                        config.getIssuer(), tokenIssuer);
                                return unauthorized(exchange, "INVALID_ISSUER",
                                        "JWT issuer does not match expected value");
                            }
                        }

                        // ── Audience validation ──────────────────────────────
                        if (config.getAudience() != null && !config.getAudience().isBlank()) {
                            var audience = claims.getAudience();
                            if (audience == null || !audience.contains(config.getAudience())) {
                                log.debug("JWT rejected — audience mismatch: expected={}, got={}",
                                        config.getAudience(), audience);
                                return unauthorized(exchange, "INVALID_AUDIENCE",
                                        "JWT audience does not contain expected value");
                            }
                        }

                        // ── Determine require-jti (per-filter override or global) ─
                        boolean requireJti = config.getRequireJti() != null
                                ? config.getRequireJti()
                                : globalRequireJti;

                        // ── JTI + Blocklist check ────────────────────────────
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

                        // No jti claim
                        if (requireJti) {
                            log.debug("JWT rejected — missing jti claim (sub={})", claims.getSubject());
                            return unauthorized(exchange, "MISSING_JTI",
                                    "Token must contain a jti claim");
                        }

                        // require-jti=false: proceed without blocklist check (with warning)
                        log.warn("JWT has no jti claim — skipping blocklist check (sub={})",
                                claims.getSubject());
                        ServerHttpRequest mutatedRequest = injectAuthHeaders(request, claims);
                        log.debug("JWT validated: sub={} tenant={} role={}",
                                claims.getSubject(),
                                claims.get("tenantId"),
                                claims.get("role"));
                        return chain.filter(exchange.mutate().request(mutatedRequest).build());
                    })
                    .onErrorResume(e -> {
                        log.error("JWT signing key resolution failed: {}", e.getMessage());
                        return unauthorized(exchange, "TOKEN_VALIDATION_FAILED",
                                "Failed to resolve JWT signing key");
                    });
        };
    }

    // ─── Token extraction ──────────────────────────────────────────────────────

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

    // ─── Signing key resolution ────────────────────────────────────────────────

    /**
     * Resolves the RSA public key for JWT signature verification.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>If JWKS URI is configured: extract {@code kid} from JWT header, look up in cache,
     *       fetch from JWKS endpoint if not cached, return resolved key.</li>
     *   <li>If JWKS lookup fails and static key is available: fall back to static key.</li>
     *   <li>If only static key is configured: return it directly.</li>
     * </ol>
     */
    private Mono<PublicKey> resolveSigningKey(String token) {
        boolean hasJwks = jwksUri != null && !jwksUri.isBlank();

        if (!hasJwks) {
            // Static key only
            return Mono.justOrEmpty(staticPublicKey);
        }

        // Extract kid from JWT header
        String kid = extractKidFromHeader(token);
        String cacheKey = kid != null ? kid : DEFAULT_KID;

        // Check Caffeine cache first
        PublicKey cached = jwksKeyCache.getIfPresent(cacheKey);
        if (cached != null) {
            return Mono.just(cached);
        }

        // Fetch from JWKS endpoint (single-flight per kid)
        return inflightJwksFetches.computeIfAbsent(cacheKey, k ->
                fetchJwksKey(kid)
                        .doOnNext(key -> jwksKeyCache.put(cacheKey, key))
                        .doFinally(signal -> inflightJwksFetches.remove(cacheKey))
                        .cache()
        ).onErrorResume(e -> {
            // Fallback to static key if JWKS fetch fails
            if (staticPublicKey != null) {
                log.warn("JWKS fetch failed, falling back to static public key: {}", e.getMessage());
                return Mono.just(staticPublicKey);
            }
            return Mono.error(e);
        });
    }

    /**
     * Extracts the {@code kid} (Key ID) from the JWT header without verifying the signature.
     */
    private String extractKidFromHeader(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) return null;
            String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            JsonNode header = objectMapper.readTree(headerJson);
            JsonNode kidNode = header.get("kid");
            return kidNode != null && !kidNode.isNull() ? kidNode.asText() : null;
        } catch (Exception e) {
            log.debug("Failed to extract kid from JWT header: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Fetches the JWKS from the configured URI and resolves the RSA public key matching the given kid.
     */
    private Mono<PublicKey> fetchJwksKey(String kid) {
        return webClient.get()
                .uri(jwksUri)
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(body -> {
                    try {
                        JsonNode jwks = objectMapper.readTree(body);
                        JsonNode keys = jwks.get("keys");
                        if (keys == null || !keys.isArray() || keys.isEmpty()) {
                            return Mono.error(new IllegalStateException("JWKS endpoint returned no keys"));
                        }

                        // Find the matching key by kid, or use the first RSA key if no kid specified
                        JsonNode matchedKey = null;
                        for (JsonNode keyNode : keys) {
                            String keyType = keyNode.has("kty") ? keyNode.get("kty").asText() : "";
                            if (!"RSA".equals(keyType)) continue;

                            if (kid == null) {
                                // No kid in token — use first RSA key
                                matchedKey = keyNode;
                                break;
                            }
                            String keyKid = keyNode.has("kid") ? keyNode.get("kid").asText() : null;
                            if (kid.equals(keyKid)) {
                                matchedKey = keyNode;
                                break;
                            }
                        }

                        if (matchedKey == null) {
                            return Mono.error(new IllegalStateException(
                                    "No matching RSA key found in JWKS for kid: " + kid));
                        }

                        // Parse RSA public key from JWK (n, e modulus/exponent)
                        PublicKey publicKey = parseRsaPublicKeyFromJwk(matchedKey);

                        // Cache all keys from the JWKS response while we're at it
                        cacheAllJwksKeys(keys);

                        return Mono.just(publicKey);
                    } catch (Exception e) {
                        return Mono.error(new IllegalStateException("Failed to parse JWKS response", e));
                    }
                });
    }

    /**
     * Parses an RSA public key from a JWK JSON node containing {@code n} (modulus) and {@code e} (exponent).
     */
    private PublicKey parseRsaPublicKeyFromJwk(JsonNode jwk) throws Exception {
        String n = jwk.get("n").asText();
        String e = jwk.get("e").asText();

        byte[] modulusBytes = Base64.getUrlDecoder().decode(n);
        byte[] exponentBytes = Base64.getUrlDecoder().decode(e);

        BigInteger modulus = new BigInteger(1, modulusBytes);
        BigInteger exponent = new BigInteger(1, exponentBytes);

        RSAPublicKeySpec spec = new RSAPublicKeySpec(modulus, exponent);
        return KeyFactory.getInstance("RSA").generatePublic(spec);
    }

    /**
     * Populates the Caffeine cache with all RSA keys from a JWKS response for faster subsequent lookups.
     */
    private void cacheAllJwksKeys(JsonNode keys) {
        for (JsonNode keyNode : keys) {
            try {
                String keyType = keyNode.has("kty") ? keyNode.get("kty").asText() : "";
                if (!"RSA".equals(keyType)) continue;

                String keyKid = keyNode.has("kid") ? keyNode.get("kid").asText() : null;
                if (keyKid == null) continue;

                PublicKey pk = parseRsaPublicKeyFromJwk(keyNode);
                jwksKeyCache.put(keyKid, pk);
            } catch (Exception e) {
                log.debug("Failed to cache JWKS key: {}", e.getMessage());
            }
        }
    }

    // ─── JWT parsing and validation ────────────────────────────────────────────

    private Claims parseAndValidate(String token, PublicKey key) {
        JwtParser parser = Jwts.parser().verifyWith(key).build();
        return parser.parseSignedClaims(token).getPayload();
    }

    // ─── Header injection ──────────────────────────────────────────────────────

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
        return GatewayProblemResponse.status(HttpStatus.UNAUTHORIZED)
                .errorCode(errorCode)
                .detail(detail)
                .write(exchange);
    }

    /**
     * Configuration for the JwtAuth filter.
     * Populated from the filter definition's config JSON.
     */
    public static class Config {
        private String issuer;
        private String audience;
        private String algorithm = "RS256";
        private Boolean requireJti;

        public String getIssuer()                       { return issuer; }
        public void setIssuer(String issuer)             { this.issuer = issuer; }
        public String getAudience()                      { return audience; }
        public void setAudience(String audience)         { this.audience = audience; }
        public String getAlgorithm()                     { return algorithm; }
        public void setAlgorithm(String algorithm)       { this.algorithm = algorithm; }
        /** Per-filter override — null means use global {@code routify.jwt.require-jti}. */
        public Boolean getRequireJti()                   { return requireJti; }
        public void setRequireJti(Boolean requireJti)    { this.requireJti = requireJti; }
    }
}

