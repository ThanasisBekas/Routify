package io.routify.common.security;

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

    /**
     * Prefix for tenant monthly request quota counters.
     *
     * <p>Full key format: {@code routify:quota:<tenantId>:<YYYY-MM>}
     *
     * <p>Atomically incremented by the gateway's
     * {@code TenantContextGatewayFilterFactory} on every request with a resolved
     * tenant ID. When the counter exceeds the tenant's
     * {@code TenantPlan.monthlyRequestQuota()}, the gateway returns HTTP 429.
     *
     * <p>TTL is set to end-of-month + 1 day on the first increment of each month.
     */
    public static final String QUOTA_PREFIX = "routify:quota:";

    /**
     * Prefix for tenant quota warning flags (prevents duplicate webhooks).
     *
     * <p>Full key format: {@code routify:quota:warned:<tenantId>:<YYYY-MM>}
     *
     * <p>Set to {@code "1"} when the 80% warning webhook is fired. Prevents
     * the same warning from being published on every subsequent request.
     */
    public static final String QUOTA_WARNED_PREFIX = "routify:quota:warned:";
}
