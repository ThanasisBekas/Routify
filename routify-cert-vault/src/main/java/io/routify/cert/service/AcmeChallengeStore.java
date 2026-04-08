package io.routify.cert.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * In-memory store for ACME HTTP-01 challenge tokens.
 *
 * <p>Challenge tokens are short-lived (5-minute TTL) and served by the
 * {@code /.well-known/acme-challenge/{token}} endpoint. A background cleanup
 * task evicts expired entries every 60 seconds.
 */
@Slf4j
@Component
public class AcmeChallengeStore {

    private static final long TTL_MINUTES = 5;
    private static final long CLEANUP_INTERVAL_SECONDS = 60;

    private record Entry(String content, Instant expiresAt) {}

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    public AcmeChallengeStore() {
        ScheduledExecutorService cleanup = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "acme-challenge-cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanup.scheduleAtFixedRate(this::evictExpired,
                CLEANUP_INTERVAL_SECONDS, CLEANUP_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Store a challenge token and its expected content.
     *
     * @param token   the ACME challenge token
     * @param content the challenge authorization content to serve
     */
    public void put(String token, String content) {
        store.put(token, new Entry(content, Instant.now().plusSeconds(TTL_MINUTES * 60)));
        log.debug("ACME challenge stored: token={}", token);
    }

    /**
     * Retrieve challenge content for the given token, if it exists and has not expired.
     */
    public Optional<String> get(String token) {
        Entry entry = store.get(token);
        if (entry == null) return Optional.empty();
        if (Instant.now().isAfter(entry.expiresAt())) {
            store.remove(token);
            return Optional.empty();
        }
        return Optional.of(entry.content());
    }

    /**
     * Remove a challenge token (e.g. after successful validation).
     */
    public void remove(String token) {
        store.remove(token);
    }

    private void evictExpired() {
        Instant now = Instant.now();
        store.entrySet().removeIf(e -> now.isAfter(e.getValue().expiresAt()));
    }
}

