package io.routify.route.repository;

import io.routify.route.domain.ProcessedCommand;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.UUID;

/**
 * Repository for the command idempotency ledger.
 *
 * <p>Used by Kafka command consumers to detect and skip duplicate commands.
 */
public interface ProcessedCommandRepository extends JpaRepository<ProcessedCommand, UUID> {

    /** Purge entries older than the given cutoff (typically 7 days). */
    @Modifying
    @Query("DELETE FROM ProcessedCommand p WHERE p.processedAt < :cutoff")
    int deleteOlderThan(Instant cutoff);
}

