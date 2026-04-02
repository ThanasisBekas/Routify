package gr.routify.identity.messaging;

import gr.routify.common.event.CommandEvent;
import gr.routify.common.event.KafkaTopics;
import gr.routify.common.security.RedisKeys;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Kafka consumer for auth {@link CommandEvent}s published by routify-admin-api.
 *
 * <p>Currently handles:
 * <ul>
 *   <li>{@link CommandEvent.Logout} — extracts the refresh token's JTI and stores it
 *       in Redis with a TTL equal to the remaining token lifetime.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthCommandKafkaConsumer {

    /** @deprecated Use {@link RedisKeys#BLOCKLIST_PREFIX} instead. */
    @Deprecated
    public static final String BLACKLIST_PREFIX = RedisKeys.BLOCKLIST_PREFIX;

    private final StringRedisTemplate redisTemplate;
    private final gr.routify.identity.security.JwtService jwtService;

    @KafkaListener(
            topics = KafkaTopics.AUTH_COMMANDS,
            groupId = "routify-identity-service-auth-commands",
            containerFactory = "userCommandKafkaListenerContainerFactory"
    )
    public void onAuthCommand(CommandEvent cmd, Acknowledgment ack) {
        try {
            switch (cmd) {
                case CommandEvent.Logout c -> handleLogout(c.refreshToken());
                default -> log.warn("Unknown auth command type: {}", cmd.getClass().getSimpleName());
            }

            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process auth command: {}", e.getMessage(), e);
            // Still ack to avoid poison-pill replay; bad tokens cannot be re-processed
            ack.acknowledge();
        }
    }

    // ─── Handlers ─────────────────────────────────────────────────────────────

    private void handleLogout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            log.warn("LOGOUT command received with blank refreshToken — ignoring");
            return;
        }

        try {
            Claims claims = jwtService.validateAndParseClaims(refreshToken);
            String jti = claims.getId();

            if (jti == null || jti.isBlank()) {
                log.warn("Refresh token has no JTI claim — cannot blacklist");
                return;
            }

            Instant expiry = claims.getExpiration().toInstant();
            long ttlSeconds = Duration.between(Instant.now(), expiry).getSeconds();

            if (ttlSeconds > 0) {
                String key = RedisKeys.BLOCKLIST_PREFIX + jti;
                redisTemplate.opsForValue().set(key, "1", Duration.ofSeconds(ttlSeconds));
                log.info("Refresh token JTI blacklisted: jti={} ttl={}s", jti, ttlSeconds);
            } else {
                log.debug("Refresh token already expired — no need to blacklist jti={}", jti);
            }
        } catch (Exception e) {
            log.warn("Could not blacklist refresh token (token may already be invalid): {}", e.getMessage());
        }
    }
}
