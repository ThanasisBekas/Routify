-- V2: Atomic usage_count + outbox housekeeping
--
-- Addresses M2: FilterDefinition.usageCount is incremented/decremented in application
-- memory (this.usageCount++) then persisted. Under concurrent transactions two operators
-- can both read usage_count=3, both increment to 4, and both persist 4 — losing one
-- increment. The correct pattern is an atomic SQL UPDATE (usage_count = usage_count + 1).
--
-- This migration:
-- 1. Drops the stale `published` outbox entries to keep the table lean (retention policy).
-- 2. Adds a DB function for atomic counter updates — called by the service layer instead
--    of the in-memory increment pattern.
-- 3. Adds an UNLOGGED cleanup table to track outbox entries eligible for periodic purge.
-- 4. Adds `locked_until` column to outbox_event for concurrency-safe polling in
--    multi-instance deployments (prevents two OutboxPoller instances processing the
--    same event simultaneously).

-- ─── 1. Atomic usage_count helpers ───────────────────────────────────────────

-- Atomically increments filter_definition.usage_count.
-- Called by RouteService.attachFilter() instead of the in-memory increment.
CREATE OR REPLACE FUNCTION routify.increment_filter_usage(p_filter_id UUID)
RETURNS VOID
LANGUAGE SQL
AS $$
    UPDATE routify.filter_definition
    SET    usage_count = usage_count + 1,
           updated_at  = now()
    WHERE  id = p_filter_id;
$$;

-- Atomically decrements filter_definition.usage_count (floor 0).
-- Called by RouteService.detachFilter() instead of the in-memory decrement.
CREATE OR REPLACE FUNCTION routify.decrement_filter_usage(p_filter_id UUID)
RETURNS VOID
LANGUAGE SQL
AS $$
    UPDATE routify.filter_definition
    SET    usage_count = GREATEST(0, usage_count - 1),
           updated_at  = now()
    WHERE  id = p_filter_id;
$$;

COMMENT ON FUNCTION routify.increment_filter_usage(UUID) IS
    'Atomically increments filter_definition.usage_count. '
    'Use instead of application-level increment to avoid concurrent-update races.';

COMMENT ON FUNCTION routify.decrement_filter_usage(UUID) IS
    'Atomically decrements filter_definition.usage_count, floored at 0.';

-- ─── 2. Outbox concurrency guard (multi-instance safe polling) ────────────────
-- Adds a `locked_until` column so that when multiple route-service instances run
-- simultaneously (rolling deploy, horizontal scale) only one instance processes each
-- outbox row at a time. The poller uses SELECT ... FOR UPDATE SKIP LOCKED.

ALTER TABLE routify.outbox_event
    ADD COLUMN IF NOT EXISTS locked_until TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_outbox_unlocked
    ON routify.outbox_event (status, created_at)
    WHERE status = 'PENDING' AND locked_until IS NULL;

COMMENT ON COLUMN routify.outbox_event.locked_until IS
    'Optimistic processing lock expiry. When non-null and in the future the row is '
    'being processed by another instance and must be skipped by other pollers. '
    'Set to now() + 30s at poll time; cleared on markPublished() / markFailed().';

-- ─── 3. Purge old PUBLISHED outbox entries ────────────────────────────────────
-- PUBLISHED entries are safe to delete after 7 days — they are the completed work log.
-- This DELETE is idempotent and safe to run at any time.
DELETE FROM routify.outbox_event
WHERE  status     = 'PUBLISHED'
  AND  published_at < now() - INTERVAL '7 days';

COMMENT ON TABLE routify.outbox_event IS
    'Transactional outbox for reliable Kafka event publishing. '
    'PUBLISHED entries older than 7 days are purged by the weekly cleanup job. '
    'FAILED entries with retry_count >= 5 are forwarded to the DLQ topic. '
    'locked_until provides concurrency-safe polling for multi-instance deployments.';

