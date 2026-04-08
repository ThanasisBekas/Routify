package io.routify.identity.service;

import io.routify.identity.repository.ApiKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Periodically marks expired API keys as {@code EXPIRED} in the database.
 *
 * <p>Redis handles real-time expiry via TTL — when a key's Redis entry expires, the gateway
 * rejects it as invalid. This scheduler is a write-behind sync that updates the durable
 * Postgres status so the dashboard displays the correct state.
 *
 * <p>Runs every 60 seconds by default.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyExpiryScheduler {

    private final ApiKeyRepository apiKeyRepository;

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    @Transactional
    public void expireKeys() {
        int expired = apiKeyRepository.expireActiveKeysBefore(Instant.now());
        if (expired > 0) {
            log.info("API key expiry sweep: marked {} key(s) as EXPIRED", expired);
        }
    }
}

