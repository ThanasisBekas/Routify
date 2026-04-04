-- =============================================================================
-- V6: routify_audit schema — Monitoring Views & Operational Procedures
-- =============================================================================
-- All objects live in routify_audit (owned by audit-service).
--
-- VIEWS (read-only, zero write-path impact):
--   v_audit_activity_summary    — event volume by type/tenant over rolling windows
--   v_request_error_rates       — per-route error rate and P95 latency (last 24 h)
--   v_replay_queue_status       — failed-request replay pipeline health
--   v_ai_filter_decision_stats  — AI filter decision distribution by tenant/route
--   v_dlq_hot_topics            — DLQ topics with the most recent failures
--   v_partition_inventory       — which monthly partitions exist and their row counts
--
-- PROCEDURES:
--   routify_audit.drop_old_partitions(keep_months)      — drop audit partitions older than N months
--   routify_audit.purge_old_request_logs(keep_days)     — targeted request_log row-level purge
--   routify_audit.reset_stalled_replays(stall_minutes)  — clear IN_PROGRESS replays that timed out
--   routify_audit.dlq_summary_report()                  — print a structured DLQ health report
-- =============================================================================

-------------------------------------------------------------------------------
-- VIEW: v_audit_activity_summary
-- Purpose: rolling-window event volume by type and tenant.
--          Useful for anomaly detection (event spike/drop alerts).
-------------------------------------------------------------------------------
CREATE OR REPLACE VIEW routify_audit.v_audit_activity_summary AS
SELECT
    tenant_id,
    event_type,
    aggregate_type,
    -- Counts across rolling time windows
    COUNT(*) FILTER (WHERE occurred_at >= now() - INTERVAL '1 hour')   AS events_last_1h,
    COUNT(*) FILTER (WHERE occurred_at >= now() - INTERVAL '24 hours') AS events_last_24h,
    COUNT(*) FILTER (WHERE occurred_at >= now() - INTERVAL '7 days')   AS events_last_7d,
    COUNT(*)                                                            AS events_total,
    MAX(occurred_at)                                                    AS last_event_at,
    MIN(occurred_at)                                                    AS first_event_at,
    now()                                                               AS snapshot_at
FROM routify_audit.audit_log
WHERE occurred_at >= now() - INTERVAL '7 days'    -- partition pruning: only touch recent partitions
GROUP BY tenant_id, event_type, aggregate_type
ORDER BY events_last_24h DESC;

COMMENT ON VIEW routify_audit.v_audit_activity_summary IS
    'Rolling-window domain event counts per tenant/type. '
    'The 7-day WHERE clause ensures PostgreSQL uses partition pruning. '
    'Query against a read replica.';

-------------------------------------------------------------------------------
-- VIEW: v_request_error_rates
-- Purpose: per-route error rate and latency percentile over the last 24 h.
--          Used for SLO dashboards and alert thresholds.
-------------------------------------------------------------------------------
CREATE OR REPLACE VIEW routify_audit.v_request_error_rates AS
SELECT
    tenant_id,
    route_id,
    route_name,
    route_version,
    COUNT(*)                                                        AS total_requests,
    COUNT(*) FILTER (WHERE failed = true)                           AS failed_requests,
    ROUND(
        100.0 * COUNT(*) FILTER (WHERE failed = true) / NULLIF(COUNT(*), 0),
        2
    )                                                               AS error_rate_pct,
    -- Response status distribution
    COUNT(*) FILTER (WHERE response_status BETWEEN 200 AND 299)    AS status_2xx,
    COUNT(*) FILTER (WHERE response_status BETWEEN 400 AND 499)    AS status_4xx,
    COUNT(*) FILTER (WHERE response_status >= 500)                  AS status_5xx,
    -- Latency statistics (milliseconds)
    ROUND(AVG(duration_ms), 2)                                      AS avg_latency_ms,
    MIN(duration_ms)                                                AS min_latency_ms,
    MAX(duration_ms)                                                AS max_latency_ms,
    PERCENTILE_CONT(0.50) WITHIN GROUP (ORDER BY duration_ms)       AS p50_latency_ms,
    PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY duration_ms)       AS p95_latency_ms,
    PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY duration_ms)       AS p99_latency_ms,
    -- Throughput
    COUNT(*) / GREATEST(1, EXTRACT(EPOCH FROM INTERVAL '24 hours') / 60.0)
                                                                    AS requests_per_minute,
    MAX(requested_at)                                               AS last_request_at,
    now()                                                           AS snapshot_at
FROM routify_audit.request_log
WHERE requested_at >= now() - INTERVAL '24 hours'   -- partition pruning
GROUP BY tenant_id, route_id, route_name, route_version
ORDER BY error_rate_pct DESC, total_requests DESC;

COMMENT ON VIEW routify_audit.v_request_error_rates IS
    'Per-route error rate and latency stats for the last 24 hours. '
    'error_rate_pct > 5 should trigger an alert. '
    'p95_latency_ms feeds SLO dashboards. Query against a read replica.';

-------------------------------------------------------------------------------
-- VIEW: v_replay_queue_status
-- Purpose: failed-request replay pipeline health — how many requests are
--          pending replay, how many are stalled IN_PROGRESS, and success rates.
-------------------------------------------------------------------------------
CREATE OR REPLACE VIEW routify_audit.v_replay_queue_status AS
SELECT
    tenant_id,
    -- Queue depth
    COUNT(*) FILTER (WHERE replay_status = 'PENDING')               AS replay_pending,
    COUNT(*) FILTER (WHERE replay_status = 'IN_PROGRESS')           AS replay_in_progress,
    COUNT(*) FILTER (WHERE replay_status = 'SUCCEEDED')             AS replay_succeeded,
    COUNT(*) FILTER (WHERE replay_status = 'FAILED')                AS replay_failed,
    COUNT(*) FILTER (WHERE replay_status = 'SKIPPED')               AS replay_skipped,
    -- Requests that failed and haven't been queued for replay yet
    COUNT(*) FILTER (WHERE failed = true AND replay_status IS NULL)  AS failed_not_queued,
    -- Max replay attempts reached (needs manual attention)
    COUNT(*) FILTER (WHERE replay_count >= 5)                        AS max_attempts_reached,
    -- Age of oldest pending replay — staleness signal
    EXTRACT(EPOCH FROM (now() - MIN(replayed_at) FILTER (WHERE replay_status = 'PENDING')))
                                                                     AS oldest_pending_age_seconds,
    -- IN_PROGRESS entries older than 5 min are likely stalled
    COUNT(*) FILTER (
        WHERE replay_status = 'IN_PROGRESS'
          AND replayed_at   < now() - INTERVAL '5 minutes'
    )                                                                AS stalled_in_progress,
    now()                                                            AS snapshot_at
FROM routify_audit.request_log
WHERE requested_at >= now() - INTERVAL '30 days'   -- partition pruning
GROUP BY tenant_id;

COMMENT ON VIEW routify_audit.v_replay_queue_status IS
    'Failed-request replay pipeline health per tenant. '
    'stalled_in_progress > 0 → call routify_audit.reset_stalled_replays(). '
    'max_attempts_reached > 0 → manual review required.';

-------------------------------------------------------------------------------
-- VIEW: v_ai_filter_decision_stats
-- Purpose: AI filter decision distribution for the last 30 days — feeds the
--          admin-api AI stats endpoints and compliance dashboards.
-------------------------------------------------------------------------------
CREATE OR REPLACE VIEW routify_audit.v_ai_filter_decision_stats AS
SELECT
    tenant_id,
    route_id,
    route_name,
    route_version,
    evaluation_mode,
    -- Decision distribution
    COUNT(*)                                                    AS total_evaluations,
    COUNT(*) FILTER (WHERE action = 'ALLOW')                   AS allow_count,
    COUNT(*) FILTER (WHERE action = 'BLOCK')                   AS block_count,
    COUNT(*) FILTER (WHERE action = 'FLAG')                    AS flag_count,
    -- Rates
    ROUND(100.0 * COUNT(*) FILTER (WHERE action = 'BLOCK')
          / NULLIF(COUNT(*), 0), 2)                            AS block_rate_pct,
    ROUND(100.0 * COUNT(*) FILTER (WHERE action = 'FLAG')
          / NULLIF(COUNT(*), 0), 2)                            AS flag_rate_pct,
    -- Cache performance
    ROUND(100.0 * COUNT(*) FILTER (WHERE cached = true)
          / NULLIF(COUNT(*), 0), 2)                            AS cache_hit_rate_pct,
    -- Latency (ms)
    ROUND(AVG(latency_ms), 2)                                  AS avg_latency_ms,
    PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY latency_ms)   AS p95_latency_ms,
    -- Confidence
    ROUND(AVG(confidence), 3)                                  AS avg_confidence,
    MAX(evaluated_at)                                          AS last_evaluated_at,
    now()                                                      AS snapshot_at
FROM routify_audit.ai_filter_decision
WHERE evaluated_at >= now() - INTERVAL '30 days'   -- partition pruning
GROUP BY tenant_id, route_id, route_name, route_version, evaluation_mode
ORDER BY block_count DESC;

COMMENT ON VIEW routify_audit.v_ai_filter_decision_stats IS
    'AI filter evaluation distribution over the last 30 days. '
    'block_rate_pct, cache_hit_rate_pct, and p95_latency_ms are the primary KPIs. '
    'Used by admin-api AI stats endpoints and the compliance dashboard.';

-------------------------------------------------------------------------------
-- VIEW: v_dlq_hot_topics
-- Purpose: DLQ topics with the most recent failures — quick triage view for
--          on-call engineers.
-------------------------------------------------------------------------------
CREATE OR REPLACE VIEW routify_audit.v_dlq_hot_topics AS
SELECT
    source_topic,
    dlq_topic,
    COUNT(*)                                                    AS total_failures,
    COUNT(*) FILTER (WHERE failed_at >= now() - INTERVAL '1 hour')
                                                                AS failures_last_1h,
    COUNT(*) FILTER (WHERE failed_at >= now() - INTERVAL '24 hours')
                                                                AS failures_last_24h,
    MAX(failed_at)                                              AS last_failure_at,
    MIN(failed_at)                                              AS first_failure_at,
    -- Most common error class
    MODE() WITHIN GROUP (ORDER BY error_class)                  AS most_common_error_class,
    now()                                                       AS snapshot_at
FROM routify_audit.dlq_event
WHERE failed_at >= now() - INTERVAL '7 days'   -- partition pruning
GROUP BY source_topic, dlq_topic
ORDER BY failures_last_1h DESC, failures_last_24h DESC;

COMMENT ON VIEW routify_audit.v_dlq_hot_topics IS
    'DLQ topics ranked by recent failure frequency. '
    'failures_last_1h is the primary triage signal for on-call engineers.';

-------------------------------------------------------------------------------
-- VIEW: v_partition_inventory
-- Purpose: shows which monthly partitions exist across all partitioned audit
--          tables and their estimated row counts.
--          Used by the partition maintenance runbook to decide which partitions
--          to drop (retention enforcement).
-------------------------------------------------------------------------------
CREATE OR REPLACE VIEW routify_audit.v_partition_inventory AS
SELECT
    nmsp_parent.nspname                       AS schema_name,
    parent.relname                            AS parent_table,
    child.relname                             AS partition_name,
    pg_get_expr(child.relpartbound, child.oid, true)
                                              AS partition_bounds,
    pg_size_pretty(pg_total_relation_size(child.oid))
                                              AS partition_size,
    pg_total_relation_size(child.oid)         AS partition_size_bytes,
    -- Approximate row count from pg_class (fast, no table scan)
    child.reltuples::BIGINT                   AS estimated_row_count
FROM pg_inherits
JOIN pg_class       parent ON pg_inherits.inhparent = parent.oid
JOIN pg_class       child  ON pg_inherits.inhrelid  = child.oid
JOIN pg_namespace   nmsp_parent ON nmsp_parent.oid  = parent.relnamespace
WHERE nmsp_parent.nspname = 'routify_audit'
ORDER BY parent.relname, child.relname;

COMMENT ON VIEW routify_audit.v_partition_inventory IS
    'Inventory of all routify_audit monthly partitions with sizes and estimated row counts. '
    'Used by the partition maintenance runbook and routify_audit.drop_old_partitions().';

-- =============================================================================
-- PROCEDURES
-- =============================================================================

-------------------------------------------------------------------------------
-- PROCEDURE: routify_audit.drop_old_partitions
-- Purpose  : Drop monthly partitions older than keep_months across all partitioned
--            audit tables (audit_log, request_log, dlq_event, ai_filter_decision,
--            ai_modifier_decision).
--
--            This is the RECOMMENDED way to enforce retention — dropping a partition
--            is instantaneous and requires no VACUUM, unlike row-level DELETE.
--
--            Safety guards:
--              • keep_months >= 3 enforced to prevent accidental over-deletion.
--              • p_dry_run = true prints what would be dropped without executing.
--              • Each DROP is logged with RAISE NOTICE.
--
-- Usage    : CALL routify_audit.drop_old_partitions(6, true);   -- dry-run: show partitions older than 6 months
--            CALL routify_audit.drop_old_partitions(6, false);  -- live: drop partitions older than 6 months
-------------------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE routify_audit.drop_old_partitions(
    keep_months  INTEGER DEFAULT 6,
    p_dry_run    BOOLEAN DEFAULT true
)
LANGUAGE plpgsql
AS $$
DECLARE
    v_cutoff_year   INTEGER;
    v_cutoff_month  INTEGER;
    v_cutoff_dt     DATE;
    v_tables        TEXT[] := ARRAY[
        'audit_log', 'request_log', 'dlq_event',
        'ai_filter_decision', 'ai_modifier_decision'
    ];
    v_table         TEXT;
    v_partition     TEXT;
    v_part_year     INTEGER;
    v_part_month    INTEGER;
    v_part_date     DATE;
    v_dropped       INTEGER := 0;
    rec             RECORD;
BEGIN
    IF keep_months < 3 THEN
        RAISE EXCEPTION 'keep_months must be >= 3 to prevent accidental data loss (got %)', keep_months;
    END IF;

    -- Compute the oldest partition to KEEP
    v_cutoff_dt := date_trunc('month', now()) - (keep_months || ' months')::INTERVAL;

    RAISE NOTICE 'drop_old_partitions: mode=% keep_months=% cutoff_date=%',
                 CASE WHEN p_dry_run THEN 'DRY-RUN' ELSE 'LIVE' END,
                 keep_months, v_cutoff_dt;

    FOREACH v_table IN ARRAY v_tables
    LOOP
        -- Find all child partitions of this table in routify_audit
        FOR rec IN
            SELECT child.relname AS partition_name
            FROM   pg_inherits
            JOIN   pg_class     parent ON pg_inherits.inhparent = parent.oid
            JOIN   pg_class     child  ON pg_inherits.inhrelid  = child.oid
            JOIN   pg_namespace ns     ON ns.oid = parent.relnamespace
            WHERE  ns.nspname   = 'routify_audit'
              AND  parent.relname = v_table
            ORDER BY child.relname
        LOOP
            v_partition := rec.partition_name;

            -- Parse year and month from partition name suffix: _yYYYYmMM
            -- Pattern: tablename_yYYYYmMM  e.g. audit_log_y2026m01
            BEGIN
                v_part_year  := substring(v_partition FROM '_y(\d{4})m\d{2}$')::INTEGER;
                v_part_month := substring(v_partition FROM '_y\d{4}m(\d{2})$')::INTEGER;
                v_part_date  := make_date(v_part_year, v_part_month, 1);
            EXCEPTION WHEN OTHERS THEN
                RAISE NOTICE 'Skipping non-standard partition name: %', v_partition;
                CONTINUE;
            END;

            IF v_part_date < v_cutoff_dt THEN
                IF p_dry_run THEN
                    RAISE NOTICE 'DRY-RUN: would DROP TABLE routify_audit.%  (partition_month=%)',
                                 v_partition, v_part_date;
                ELSE
                    EXECUTE format('DROP TABLE IF EXISTS routify_audit.%I', v_partition);
                    RAISE NOTICE 'DROPPED: routify_audit.%  (partition_month=%)',
                                 v_partition, v_part_date;
                    v_dropped := v_dropped + 1;
                END IF;
            END IF;
        END LOOP;
    END LOOP;

    IF p_dry_run THEN
        RAISE NOTICE 'drop_old_partitions: DRY-RUN complete — no changes made';
    ELSE
        RAISE NOTICE 'drop_old_partitions: dropped % partition(s)', v_dropped;
    END IF;
END;
$$;

COMMENT ON PROCEDURE routify_audit.drop_old_partitions(INTEGER, BOOLEAN) IS
    'Drops monthly audit partitions older than keep_months (default 6). '
    'Always run with p_dry_run=true first to review which partitions will be dropped. '
    'keep_months must be >= 3. Each DROP is instantaneous — no VACUUM needed.';

-------------------------------------------------------------------------------
-- PROCEDURE: routify_audit.purge_old_request_logs
-- Purpose  : Row-level DELETE of request_log entries older than keep_days.
--            Use this only when you want to retain a partition but delete specific
--            rows (e.g. purge a tenant's data on GDPR deletion request).
--            For bulk retention enforcement, prefer drop_old_partitions.
--
-- Usage    : CALL routify_audit.purge_old_request_logs(30);                  -- all tenants
--            CALL routify_audit.purge_old_request_logs(30, 'tenant-uuid');   -- single tenant
--            CALL routify_audit.purge_old_request_logs(30, NULL, true);      -- dry-run
-------------------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE routify_audit.purge_old_request_logs(
    keep_days    INTEGER,
    p_tenant_id  UUID    DEFAULT NULL,
    p_dry_run    BOOLEAN DEFAULT false
)
LANGUAGE plpgsql
AS $$
DECLARE
    v_cutoff  TIMESTAMPTZ := now() - (keep_days || ' days')::INTERVAL;
    v_count   INTEGER;
    v_deleted INTEGER;
BEGIN
    IF keep_days < 7 THEN
        RAISE EXCEPTION 'keep_days must be >= 7 to prevent accidental data loss (got %)', keep_days;
    END IF;

    RAISE NOTICE 'purge_old_request_logs: mode=% keep_days=% cutoff=% tenant=%',
                 CASE WHEN p_dry_run THEN 'DRY-RUN' ELSE 'LIVE' END,
                 keep_days, v_cutoff, COALESCE(p_tenant_id::TEXT, 'ALL');

    SELECT COUNT(*) INTO v_count
    FROM   routify_audit.request_log
    WHERE  requested_at < v_cutoff
      AND  (p_tenant_id IS NULL OR tenant_id = p_tenant_id);

    RAISE NOTICE 'purge_old_request_logs: % row(s) eligible', v_count;

    IF p_dry_run OR v_count = 0 THEN
        RAISE NOTICE 'purge_old_request_logs: % — done',
                     CASE WHEN p_dry_run THEN 'DRY-RUN, no changes' ELSE 'nothing to delete' END;
        RETURN;
    END IF;

    DELETE FROM routify_audit.request_log
    WHERE  requested_at < v_cutoff
      AND  (p_tenant_id IS NULL OR tenant_id = p_tenant_id);

    GET DIAGNOSTICS v_deleted = ROW_COUNT;

    RAISE NOTICE 'purge_old_request_logs: deleted % row(s)', v_deleted;
END;
$$;

COMMENT ON PROCEDURE routify_audit.purge_old_request_logs(INTEGER, UUID, BOOLEAN) IS
    'Row-level DELETE of request_log entries older than keep_days. '
    'Use p_tenant_id for GDPR/right-to-erasure tenant data deletions. '
    'For bulk retention, prefer drop_old_partitions() which is instantaneous.';

-------------------------------------------------------------------------------
-- PROCEDURE: routify_audit.reset_stalled_replays
-- Purpose  : Reset IN_PROGRESS replay rows that have been stuck for longer than
--            stall_minutes back to PENDING so they are retried.
--            A replay is considered stalled when replayed_at < now() - stall_minutes.
--
-- Usage    : CALL routify_audit.reset_stalled_replays();       -- default 5 min
--            CALL routify_audit.reset_stalled_replays(10);     -- custom threshold
-------------------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE routify_audit.reset_stalled_replays(
    stall_minutes  INTEGER DEFAULT 5
)
LANGUAGE plpgsql
AS $$
DECLARE
    v_stall_cutoff  TIMESTAMPTZ := now() - (stall_minutes || ' minutes')::INTERVAL;
    v_reset         INTEGER;
BEGIN
    RAISE NOTICE 'reset_stalled_replays: resetting IN_PROGRESS replays stalled for > % minutes', stall_minutes;

    UPDATE routify_audit.request_log
    SET    replay_status = 'PENDING',
           replay_error  = 'Reset by reset_stalled_replays() after ' || stall_minutes || ' min stall'
    WHERE  replay_status = 'IN_PROGRESS'
      AND  replayed_at   < v_stall_cutoff
      AND  requested_at  >= now() - INTERVAL '30 days';  -- partition pruning

    GET DIAGNOSTICS v_reset = ROW_COUNT;

    RAISE NOTICE 'reset_stalled_replays: reset % stalled replay(s) to PENDING', v_reset;
END;
$$;

COMMENT ON PROCEDURE routify_audit.reset_stalled_replays(INTEGER) IS
    'Resets IN_PROGRESS replay rows stalled for > stall_minutes back to PENDING. '
    'A replay is stalled when the FailedRequestReplayService crashed mid-flight. '
    'Safe to call from a health-check cron every 5 minutes.';

-------------------------------------------------------------------------------
-- PROCEDURE: routify_audit.dlq_summary_report
-- Purpose  : Print a structured DLQ health report — called from the ops runbook
--            at the start of an incident or daily health-check.
--
-- Usage    : CALL routify_audit.dlq_summary_report();
--            CALL routify_audit.dlq_summary_report(24);  -- last 24 h only
-------------------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE routify_audit.dlq_summary_report(
    window_hours  INTEGER DEFAULT 48
)
LANGUAGE plpgsql
AS $$
DECLARE
    v_since   TIMESTAMPTZ := now() - (window_hours || ' hours')::INTERVAL;
    v_total   INTEGER;
    rec       RECORD;
BEGIN
    SELECT COUNT(*) INTO v_total
    FROM   routify_audit.dlq_event
    WHERE  failed_at >= v_since;

    RAISE NOTICE '=== DLQ Summary Report (last % h) ===', window_hours;
    RAISE NOTICE 'Total DLQ events: %', v_total;

    IF v_total = 0 THEN
        RAISE NOTICE 'No DLQ events in the window — system healthy.';
        RETURN;
    END IF;

    RAISE NOTICE '';
    RAISE NOTICE '--- By Source Topic ---';
    FOR rec IN
        SELECT source_topic,
               COUNT(*)                                        AS total,
               COUNT(*) FILTER (WHERE failed_at >= now() - INTERVAL '1 hour') AS last_1h,
               MAX(failed_at)                                  AS last_failure,
               MODE() WITHIN GROUP (ORDER BY error_class)      AS top_error_class
        FROM   routify_audit.dlq_event
        WHERE  failed_at >= v_since
        GROUP BY source_topic
        ORDER BY total DESC
    LOOP
        RAISE NOTICE '  topic=%-40s  total=%-5s  last_1h=%-4s  last_failure=%  top_error=%',
                     rec.source_topic, rec.total, rec.last_1h,
                     rec.last_failure, COALESCE(rec.top_error_class, 'unknown');
    END LOOP;

    RAISE NOTICE '';
    RAISE NOTICE '--- Top Error Classes ---';
    FOR rec IN
        SELECT error_class, COUNT(*) AS occurrences
        FROM   routify_audit.dlq_event
        WHERE  failed_at  >= v_since
          AND  error_class IS NOT NULL
        GROUP BY error_class
        ORDER BY occurrences DESC
        LIMIT 10
    LOOP
        RAISE NOTICE '  %-60s  count=%', rec.error_class, rec.occurrences;
    END LOOP;

    RAISE NOTICE '=== End DLQ Report ===';
END;
$$;

COMMENT ON PROCEDURE routify_audit.dlq_summary_report(INTEGER) IS
    'Prints a structured DLQ health report via RAISE NOTICE. '
    'Covers total event count, per-topic breakdown, and top error classes. '
    'Call at the start of an incident or as part of the daily health-check runbook.';

