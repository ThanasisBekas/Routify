package gr.routify.common.security;

/**
 * Shared Redis key prefixes used across Routify services.
 *
 * <p>Centralising these constants in routify-common ensures that the
 * identity-service (write) and the api-gateway (read) always reference
 * the same key namespace, eliminating hard-coded string duplication.
 */
public final class RedisKeys {

    private RedisKeys() {}

    /**
     * Prefix for the JWT token blocklist.
     *
     * <p>Full key format: {@code routify:token:blocklist:<jti>}
     *
     * <p>Written by {@code AuthService.revokeAccessToken} /
     * {@code AuthService.revokeRefreshToken} and
     * {@code AuthCommandKafkaConsumer.handleLogout} with a TTL equal
     * to the token's remaining lifetime.
     *
     * <p>Read by {@code JwtAuthGatewayFilterFactory} on every incoming
     * request: if the key exists the token has been revoked and the
     * gateway returns HTTP 401 immediately.
     */
    public static final String BLOCKLIST_PREFIX = "routify:token:blocklist:";

    /**
     * Prefix for API key hashes used by the gateway's
     * {@code ApiKeyAuthGatewayFilterFactory} for authentication.
     *
     * <p>Full key format: {@code routify:apikeys:<api-key-value>}
     *
     * <p>Each key is a Redis Hash with fields:
     * <ul>
     *   <li>{@code tenantId} — UUID of the owning tenant</li>
     *   <li>{@code userId}   — UUID of the user the key acts as</li>
     *   <li>{@code role}     — role granted to requests using this key (e.g. OPERATOR)</li>
     *   <li>{@code email}    — email for audit attribution</li>
     *   <li>{@code expiresAt} — (optional) epoch-second expiry; if absent the key
     *       relies solely on Redis TTL for expiration</li>
     * </ul>
     *
     * <p>Written by the identity-service API key management (future) or seeded
     * manually via {@code redis-cli HSET routify:apikeys:<key> tenantId ... userId ... role ... email ...}.
     *
     * <p>Read by {@code ApiKeyAuthGatewayFilterFactory} on every API-key-authenticated request.
     */
    public static final String APIKEY_PREFIX = "routify:apikeys:";
}
