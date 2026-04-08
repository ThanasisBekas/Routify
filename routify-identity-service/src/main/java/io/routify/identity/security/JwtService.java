package io.routify.identity.security;

import io.routify.common.exception.RoutifyException;
import io.routify.identity.domain.AppUser;
import io.routify.identity.service.RoleService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * JWT service — issues RS256 signed access and refresh tokens.
 *
 * <p>Uses RSA key pair (2048-bit):
 * <ul>
 *   <li>Private key: used to sign tokens (stored in Vault in production)</li>
 *   <li>Public key: distributed to all services for verification</li>
 * </ul>
 *
 * <p>Phase 3.7 fix: RSA keys are now parsed and cached at {@code @PostConstruct}
 * startup time. If keys are present but malformed, the service fails fast with
 * a descriptive {@link IllegalStateException}. If no keys are configured, an
 * ephemeral dev key pair is generated and a warning is logged.
 *
 * <p>Token claims:
 * <ul>
 *   <li>{@code sub} — user ID</li>
 *   <li>{@code tenantId} — tenant ID</li>
 *   <li>{@code role} — user role</li>
 *   <li>{@code email} — user email</li>
 *   <li>{@code username} — user username</li>
 *   <li>{@code jti} — unique token ID (for revocation)</li>
 * </ul>
 */
@Slf4j
@Component
public class JwtService {

    private final RoleService roleService;

    public JwtService(RoleService roleService) {
        this.roleService = roleService;
    }

    @Value("${routify.jwt.private-key:}")
    private String privateKeyBase64;

    @Value("${routify.jwt.public-key:}")
    private String publicKeyBase64;

    @Value("${routify.jwt.access-token-ttl-seconds:3600}")
    private long accessTokenTtlSeconds;

    @Value("${routify.jwt.refresh-token-ttl-seconds:86400}")
    private long refreshTokenTtlSeconds;

    /**
     * Controls the {@code Secure} flag on the refresh_token cookie.
     * Must be {@code true} in any HTTPS deployment.
     * Defaults to {@code false} so local HTTP dev (localhost) works without TLS.
     */
    @Value("${routify.jwt.refresh-cookie-secure:false}")
    private boolean refreshCookieSecure;

    /**
     * When {@code true}, the JWT {@code permissions} claim is populated with the
     * resolved permission set for the user's role. When {@code false}, the claim
     * is omitted and all services fall back to role-based {@code hasRole()} checks.
     */
    @Value("${routify.rbac.granular-enabled:false}")
    private boolean granularRbacEnabled;

    /** Cached private key — parsed once at startup. */
    private PrivateKey privateKey;

    /** Cached public key — parsed once at startup. */
    private PublicKey publicKey;

    /**
     * Parses and caches the RSA key pair at startup. Fails fast with a clear error
     * if keys are present but malformed. If no keys are configured, generates an
     * ephemeral dev key pair.
     */
    @PostConstruct
    public void init() {
        if (privateKeyBase64 == null || privateKeyBase64.isBlank()) {
            log.warn("DEV MODE: No JWT_PRIVATE_KEY set — generating ephemeral RSA key pair. " +
                     "Set routify.jwt.private-key and routify.jwt.public-key in production!");
            KeyPair pair = generateDevKeyPair();
            this.privateKey = pair.getPrivate();
            this.publicKey  = pair.getPublic();
        } else {
            try {
                byte[] keyBytes = Base64.getDecoder().decode(privateKeyBase64.trim());
                this.privateKey = KeyFactory.getInstance("RSA")
                        .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Invalid JWT_PRIVATE_KEY — must be a base64-encoded PKCS8 RSA private key. " +
                        "Generate with: openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out private.pem && " +
                        "openssl pkcs8 -topk8 -inform PEM -outform DER -nocrypt -in private.pem | base64 | tr -d '\\n'", e);
            }
            try {
                byte[] keyBytes = Base64.getDecoder().decode(
                        (publicKeyBase64 != null ? publicKeyBase64 : "").trim());
                this.publicKey = KeyFactory.getInstance("RSA")
                        .generatePublic(new X509EncodedKeySpec(keyBytes));
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Invalid JWT_PUBLIC_KEY — must be a base64-encoded X509 RSA public key. " +
                        "Generate with: openssl rsa -in private.pem -pubout -outform DER | base64 | tr -d '\\n'", e);
            }
        }
        log.info("JwtService initialised (keyType={}, accessTTL={}s, refreshTTL={}s)",
                privateKey.getAlgorithm(), accessTokenTtlSeconds, refreshTokenTtlSeconds);
    }

    /**
     * Issues a short-lived access token for the given user.
     * Token is RS256 signed.
     */
    public String issueAccessToken(AppUser user) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(user.getId().toString())
                .claim("tenantId",  user.getTenantId() != null ? user.getTenantId().toString() : null)
                .claim("role",      user.getRole().name())
                .claim("email",     user.getEmail())
                .claim("username",  user.getUsername())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(accessTokenTtlSeconds)))
                .id(UUID.randomUUID().toString())
                .signWith(privateKey);

        // Granular RBAC: embed resolved permissions in JWT when feature flag is enabled
        if (granularRbacEnabled) {
            try {
                List<String> permissions = roleService.getPermissionsForRole(
                        user.getRole(), user.getTenantId());
                builder.claim("permissions", permissions);
            } catch (Exception e) {
                log.warn("Failed to resolve permissions for user={}, role={}: {}",
                        user.getId(), user.getRole(), e.getMessage());
                // Continue without permissions — services will fall back to role-based checks
            }
        }

        return builder.compact();
    }

    /**
     * Issues a long-lived refresh token.
     * Minimal claims — only subject and JTI for revocation tracking.
     */
    public String issueRefreshToken(AppUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("type", "REFRESH")
                .claim("tenantId", user.getTenantId() != null ? user.getTenantId().toString() : null)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(refreshTokenTtlSeconds)))
                .id(UUID.randomUUID().toString())
                .signWith(privateKey)
                .compact();
    }

    /**
     * Validates a token and returns its claims.
     *
     * @throws io.jsonwebtoken.JwtException if the token is invalid or expired
     */
    public Claims validateAndParseClaims(String token) throws Exception {
        return Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long getAccessTokenTtlSeconds()   { return accessTokenTtlSeconds; }
    public long getRefreshTokenTtlSeconds()  { return refreshTokenTtlSeconds; }
    public boolean isRefreshCookieSecure()   { return refreshCookieSecure; }

    private static KeyPair generateDevKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new RoutifyException.GatewayError("Failed to generate dev key pair", e);
        }
    }
}

