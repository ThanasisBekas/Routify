-- V5: Partition ai_modifier_decision + add route_version to high-volume audit tables
--
-- Addresses two issues:
--
-- 1. H1 — ai_modifier_decision was a plain heap table while every other high-volume
--    audit table (audit_log, request_log, dlq_event, ai_filter_decision) is partitioned
--    by month. This migration converts it to a partitioned table for consistent retention
--    management via partition pruning (drop old partition = instant reclaim, no VACUUM).
--
-- 2. M1 — request_log, ai_filter_decision, and ai_modifier_decision store route_name
--    as a plain VARCHAR captured at request time. When a route is renamed the old name
--    is preserved (correct for point-in-time records), but there was no way to correlate
--    the record against the exact route version that served it. Adding route_version
--    enables join-free version lookups in analytics queries.
--
-- IMPORTANT: ai_modifier_decision must be re-created because PostgreSQL does not support
-- converting a regular table to a partitioned table in-place. The migration:
--   1. Renames the existing table to _old.
--   2. Creates the new partitioned table with the same columns + route_version.
--   3. Copies all existing rows.
--   4. Drops the _old table.
-- This is a NON-DESTRUCTIVE migration — no data is lost.

-- ─── Step 1: rename existing heap table ──────────────────────────────────────
ALTER TABLE routify_audit.ai_modifier_decision
    RENAME TO ai_modifier_decision_old;

-- ─── Step 2: create the new partitioned table ────────────────────────────────
CREATE TABLE IF NOT EXISTS routify_audit.ai_modifier_decision (
    id                  UUID        NOT NULL DEFAULT gen_random_uuid(),
    mutation_id         TEXT,
    route_id            UUID,
    route_name          TEXT,
    route_version       INTEGER,               -- NEW: the route.version that was active when the modifier ran
    tenant_id           UUID,
    mutation_applied    BOOLEAN     NOT NULL,
    mutation_type       VARCHAR(30),
    reason              TEXT,
    original_body_hash  VARCHAR(64),
    mutated_body_hash   VARCHAR(64),
    headers_modified    JSONB,
    cached              BOOLEAN     NOT NULL DEFAULT FALSE,
    latency_ms          BIGINT,
    method              VARCHAR(10),
    path                VARCHAR(500),
    client_ip           VARCHAR(45),
    evaluated_at        TIMESTAMPTZ NOT NULL,
    recorded_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_ai_modifier_decision PRIMARY KEY (id, evaluated_at)
) PARTITION BY RANGE (evaluated_at);

-- ─── Step 3: monthly partitions (April 2026 – March 2027) ────────────────────
CREATE TABLE IF NOT EXISTS routify_audit.ai_modifier_decision_y2026m04
    PARTITION OF routify_audit.ai_modifier_decision
    FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');

CREATE TABLE IF NOT EXISTS routify_audit.ai_modifier_decision_y2026m05
    PARTITION OF routify_audit.ai_modifier_decision
    FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');

CREATE TABLE IF NOT EXISTS routify_audit.ai_modifier_decision_y2026m06
    PARTITION OF routify_audit.ai_modifier_decision
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');

CREATE TABLE IF NOT EXISTS routify_audit.ai_modifier_decision_y2026m07
    PARTITION OF routify_audit.ai_modifier_decision
    FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');

CREATE TABLE IF NOT EXISTS routify_audit.ai_modifier_decision_y2026m08
    PARTITION OF routify_audit.ai_modifier_decision
    FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');

CREATE TABLE IF NOT EXISTS routify_audit.ai_modifier_decision_y2026m09
    PARTITION OF routify_audit.ai_modifier_decision
    FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');

CREATE TABLE IF NOT EXISTS routify_audit.ai_modifier_decision_y2026m10
    PARTITION OF routify_audit.ai_modifier_decision
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');

CREATE TABLE IF NOT EXISTS routify_audit.ai_modifier_decision_y2026m11
    PARTITION OF routify_audit.ai_modifier_decision
    FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');

CREATE TABLE IF NOT EXISTS routify_audit.ai_modifier_decision_y2026m12
    PARTITION OF routify_audit.ai_modifier_decision
    FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');

CREATE TABLE IF NOT EXISTS routify_audit.ai_modifier_decision_y2027m01
    PARTITION OF routify_audit.ai_modifier_decision
    FOR VALUES FROM ('2027-01-01') TO ('2027-02-01');

CREATE TABLE IF NOT EXISTS routify_audit.ai_modifier_decision_y2027m02
    PARTITION OF routify_audit.ai_modifier_decision
    FOR VALUES FROM ('2027-02-01') TO ('2027-03-01');

CREATE TABLE IF NOT EXISTS routify_audit.ai_modifier_decision_y2027m03
    PARTITION OF routify_audit.ai_modifier_decision
    FOR VALUES FROM ('2027-03-01') TO ('2027-04-01');

-- ─── Step 4: recreate indexes on the partitioned table ───────────────────────
CREATE INDEX IF NOT EXISTS idx_ai_mod_tenant_time
    ON routify_audit.ai_modifier_decision (tenant_id, evaluated_at DESC);

CREATE INDEX IF NOT EXISTS idx_ai_mod_route_time
    ON routify_audit.ai_modifier_decision (route_id, evaluated_at DESC);

CREATE INDEX IF NOT EXISTS idx_ai_mod_mutation_id
    ON routify_audit.ai_modifier_decision (mutation_id);

CREATE INDEX IF NOT EXISTS idx_ai_mod_type
    ON routify_audit.ai_modifier_decision (mutation_type, evaluated_at DESC);

-- ─── Step 5: migrate existing data ───────────────────────────────────────────
-- route_version defaults to NULL for historical rows (no version was recorded at the time).
INSERT INTO routify_audit.ai_modifier_decision (
    id, mutation_id, route_id, route_name, route_version,
    tenant_id, mutation_applied, mutation_type, reason,
    original_body_hash, mutated_body_hash, headers_modified,
    cached, latency_ms, method, path, client_ip,
    evaluated_at, recorded_at
)
SELECT
    id, mutation_id, route_id, route_name, NULL AS route_version,
    tenant_id, mutation_applied, mutation_type, reason,
    original_body_hash, mutated_body_hash, headers_modified,
    cached, latency_ms, method, path, client_ip,
    evaluated_at, recorded_at
FROM routify_audit.ai_modifier_decision_old;

-- ─── Step 6: drop the old heap table ─────────────────────────────────────────
DROP TABLE routify_audit.ai_modifier_decision_old;

-- ─── Step 7: add route_version to ai_filter_decision ─────────────────────────
-- Additive column — NULL for historical rows, populated for new evaluations.
ALTER TABLE routify_audit.ai_filter_decision
    ADD COLUMN IF NOT EXISTS route_version INTEGER;

-- ─── Step 8: add route_version to request_log ────────────────────────────────
ALTER TABLE routify_audit.request_log
    ADD COLUMN IF NOT EXISTS route_version INTEGER;

COMMENT ON COLUMN routify_audit.ai_modifier_decision.route_version IS
    'The route.version that was active when this modifier ran. '
    'Enables point-in-time correlation: which exact filter chain handled this request.';

COMMENT ON COLUMN routify_audit.ai_filter_decision.route_version IS
    'The route.version active at evaluation time. NULL for records before V5 migration.';

COMMENT ON COLUMN routify_audit.request_log.route_version IS
    'The route.version active at request time. NULL for records before V5 migration.';

COMMENT ON TABLE routify_audit.ai_modifier_decision IS
    'Immutable audit records for AI Modification Filter decisions, partitioned by month. '
    'Converted from a plain heap table in V5 to match the partitioning strategy of all '
    'other high-volume audit tables (audit_log, request_log, ai_filter_decision).';

