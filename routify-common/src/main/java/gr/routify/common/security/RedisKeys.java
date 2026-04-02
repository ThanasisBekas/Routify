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
}

