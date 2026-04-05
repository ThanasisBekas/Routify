package io.routify.audit.replay;

import io.routify.audit.domain.RequestLog;
import io.routify.audit.replay.FailedRequestReplayService.ReplayResult;
import io.routify.audit.repository.RequestLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Background scheduler that periodically retries PENDING failed requests.
 *
 * <p>Only replays requests that are at least {@code replay.min-age-minutes} old
 * (default 5 min) to allow time for transient issues to resolve before re-attempting.
 *
 * <p>Can be disabled entirely via {@code routify.replay.scheduler.enabled=false}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "routify.replay.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class ReplayScheduler {

    private final RequestLogRepository requestLogRepository;
    private final FailedRequestReplayService replayService;

    @Value("${routify.replay.scheduler.batch-size:20}")
    private int batchSize;

    @Value("${routify.replay.scheduler.min-age-minutes:5}")
    private int minAgeMinutes;

    @Value("${routify.replay.scheduler.max-attempts:5}")
    private int maxAttempts;

    /**
     * Runs every 10 minutes by default. Picks up any PENDING requests that are
     * old enough (transient failures) and re-issues them to their upstream URIs.
     */
    @Scheduled(fixedDelayString = "${routify.replay.scheduler.interval-ms:600000}")
    public void replayPendingRequests() {
        Instant before = Instant.now().minus(minAgeMinutes, ChronoUnit.MINUTES);

        List<RequestLog> candidates = requestLogRepository
                .findReplayableRequests(before, maxAttempts, batchSize);

        if (candidates.isEmpty()) {
            log.debug("Replay scheduler: no pending requests to replay");
            return;
        }

        log.info("Replay scheduler: replaying {} failed request(s)", candidates.size());

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger failed    = new AtomicInteger();
        AtomicInteger skipped   = new AtomicInteger();

        for (RequestLog entry : candidates) {
            try {
                ReplayResult result = replayService.replay(entry.getId(), entry.getTenantId());
                switch (result.outcome()) {
                    case SUCCEEDED -> succeeded.incrementAndGet();
                    case FAILED    -> failed.incrementAndGet();
                    case SKIPPED   -> skipped.incrementAndGet();
                }
            } catch (Exception e) {
                log.error("Replay scheduler error for id={}: {}", entry.getId(), e.getMessage());
                failed.incrementAndGet();
            }
        }

        log.info("Replay scheduler complete: succeeded={} failed={} skipped={}",
                succeeded.get(), failed.get(), skipped.get());
    }
}

