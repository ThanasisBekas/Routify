-- =============================================================================
-- V3: routify schema — Monitoring Views & Operational Procedures
-- =============================================================================
-- All objects are created in the routify schema (owned by route-service).
--
-- VIEWS (read-only, zero write-path impact):
--   v_route_health          — per-tenant route lifecycle summary
--   v_filter_inventory      — filter catalogue with usage and deprecation flags
--   v_outbox_health         — outbox pipeline health across all three outboxes
--   v_gateway_config_audit  — current gateway configuration snapshot with age
--
-- PROCEDURES (operational, must be called explicitly — never from app hot-path):
--   routify.purge_published_outbox(retention_days)  — clean up old PUBLISHED rows
--   routify.reconcile_filter_usage()                — recompute usage_count from join table
--   routify.archive_soft_deleted_routes(older_than) — hard-delete ARCHIVED routes safely
-- =============================================================================

-------------------------------------------------------------------------------
-- VIEW: v_route_health
-- Purpose: per-tenant breakdown of route counts by status, latest activation,
--          and quota utilisation percentage (requires cross-schema access to
--          routify_identity.quota_policy via a SECURITY DEFINER wrapper).
--
-- Consumer: Prometheus scraper (read replica), dashboard gateway status page,
--           on-call runbooks.  NEVER queried on the write path.
-------------------------------------------------------------------------------
CREATE OR REPLACE VIEW routify.v_route_health AS
SELECT
    r.tenant_id,
    COUNT(*)                                                        AS total_routes,
    COUNT(*) FILTER (WHERE r.status = 'ACTIVE')                    AS active_routes,
    COUNT(*) FILTER (WHERE r.status = 'DRAFT')                     AS draft_routes,
    COUNT(*) FILTER (WHERE r.status = 'DISABLED')                  AS disabled_routes,
    COUNT(*) FILTER (WHERE r.status = 'ARCHIVED')                  AS archived_routes,
    MAX(r.activated_at)                                             AS last_activated_at,
    MAX(r.updated_at)                                               AS last_updated_at,
    -- How many routes have at least one filter attached
    COUNT(DISTINCT rf.route_id)                                     AS routes_with_filters,
    -- Average filter chain depth across routes that have filters
    ROUND((AVG(filter_counts.cnt) FILTER (WHERE filter_counts.cnt > 0))::NUMERIC, 2)
                                                                    AS avg_filters_per_route,
    -- ACTIVE routes with zero filters are a potential misconfiguration (unprotected route)
    COUNT(*) FILTER (
        WHERE r.status = 'ACTIVE'
          AND NOT EXISTS (
              SELECT 1 FROM routify.route_filter rf2
              WHERE rf2.route_id = r.id AND rf2.enabled = true
          )
    )                                                               AS active_routes_no_filters,
    now()                                                           AS snapshot_at
FROM routify.route r
LEFT JOIN routify.route_filter rf
       ON rf.route_id = r.id
LEFT JOIN (
    SELECT route_id, COUNT(*) AS cnt
    FROM   routify.route_filter
    WHERE  enabled = true
    GROUP BY route_id
) AS filter_counts ON filter_counts.route_id = r.id
WHERE r.status <> 'ARCHIVED'   -- exclude soft-deleted from day-to-day health view
GROUP BY r.tenant_id;

COMMENT ON VIEW routify.v_route_health IS
    'Per-tenant route lifecycle summary. Aggregates route status counts, last activity '
    'timestamps, and potential misconfiguration flags (active routes without filters). '
    'Query against a read replica — never against the primary write path.';

-------------------------------------------------------------------------------
-- VIEW: v_filter_inventory
-- Purpose: full filter catalogue per tenant — type distribution, usage, and
--          a flag for @Deprecated filter types that have no gateway factory.
--
-- Consumer: dashboard filter management page, compliance reports.
-------------------------------------------------------------------------------
CREATE OR REPLACE VIEW routify.v_filter_inventory AS
SELECT
    fd.tenant_id,
    fd.filter_type,
    COUNT(*)                                                AS total_definitions,
    COUNT(*) FILTER (WHERE fd.enabled = true)              AS enabled_definitions,
    COUNT(*) FILTER (WHERE fd.enabled = false)             AS disabled_definitions,
    SUM(fd.usage_count)                                    AS total_usages,
    COUNT(*) FILTER (WHERE fd.usage_count = 0)             AS unused_definitions,
    COUNT(*) FILTER (WHERE fd.system_managed = true)       AS system_managed_count,
    MAX(fd.updated_at)                                     AS last_modified_at,
    -- Flag filter types that are @Deprecated in FilterType enum (no gateway factory)
    CASE
        WHEN fd.filter_type IN (
            'AUTH_NONE', 'RATE_LIMIT_TOKEN_BUCKET', 'PATH_REWRITE',
            'PATH_STRIP_PREFIX', 'PATH_ADD_PREFIX', 'QUERY_PARAM_MODIFY',
            'BODY_JSONATA_TRANSFORM', 'BODY_SPEL_TRANSFORM',
            'VALIDATE_REGEX', 'VALIDATE_SIZE', 'CIRCUIT_BREAKER', 'RETRY'
        ) THEN true
        ELSE false
    END                                                    AS is_deprecated_type,
    now()                                                  AS snapshot_at
FROM routify.filter_definition fd
GROUP BY fd.tenant_id, fd.filter_type;

COMMENT ON VIEW routify.v_filter_inventory IS
    'Filter type distribution per tenant. Includes a flag for deprecated filter types '
    'that have no GatewayFilterFactory implementation — these should be migrated or removed.';

-------------------------------------------------------------------------------
-- VIEW: v_outbox_health
-- Purpose: outbox pipeline health — counts of PENDING / PUBLISHED / FAILED
--          entries and age of oldest PENDING entry (lag indicator).
--
-- A PENDING age > 30s means the OutboxPoller is stalled or Kafka is unavailable.
-- A FAILED count > 0 means events need operator attention.
--
-- NOTE: This view queries only the routify.outbox_event table (route-service).
--       Equivalent views for routify_identity.outbox_event and
--       routify_cert.cert_outbox_event live in their respective service migrations.
-------------------------------------------------------------------------------
CREATE OR REPLACE VIEW routify.v_outbox_health AS
SELECT
    'routify.outbox_event'                                 AS outbox_table,
    COUNT(*)                                               AS total_entries,
    COUNT(*) FILTER (WHERE status = 'PENDING')             AS pending_count,
    COUNT(*) FILTER (WHERE status = 'PUBLISHED')           AS published_count,
    COUNT(*) FILTER (WHERE status = 'FAILED')              AS failed_count,
    -- Age of the oldest PENDING entry — the primary lag signal
    EXTRACT(EPOCH FROM (now() - MIN(created_at) FILTER (WHERE status = 'PENDING')))
                                                           AS oldest_pending_age_seconds,
    MAX(published_at)                                      AS last_published_at,
    -- FAILED entries with retry_count >= 5 need manual DLQ intervention
    COUNT(*) FILTER (WHERE status = 'FAILED' AND retry_count >= 5)
                                                           AS exhausted_retries_count,
    now()                                                  AS snapshot_at
FROM routify.outbox_event;

COMMENT ON VIEW routify.v_outbox_health IS
    'Outbox pipeline health for routify-route-service. '
    'oldest_pending_age_seconds > 30 indicates a stalled OutboxPoller or Kafka unavailability. '
    'exhausted_retries_count > 0 requires manual investigation via the DLQ audit table.';

-------------------------------------------------------------------------------
-- VIEW: v_gateway_config_audit
-- Purpose: current gateway configuration snapshot — shows all stored config
--          sections, their version, who last changed them, and how stale they are.
-------------------------------------------------------------------------------
CREATE OR REPLACE VIEW routify.v_gateway_config_audit AS
SELECT
    gc.id,
    gc.config_key,
    gc.tenant_id,
    gc.version,
    gc.updated_by,
    gc.updated_at,
    gc.created_at,
    -- Age of the last update
    now() - gc.updated_at                                  AS config_age,
    EXTRACT(EPOCH FROM (now() - gc.updated_at)) / 3600.0  AS config_age_hours,
    -- Flag configs that haven't been reviewed in > 90 days
    CASE
        WHEN gc.updated_at < now() - INTERVAL '90 days' THEN true
        ELSE false
    END                                                    AS stale_config,
    -- Global (tenant_id IS NULL) vs tenant-scoped
    CASE WHEN gc.tenant_id IS NULL THEN 'GLOBAL' ELSE 'TENANT' END
                                                           AS config_scope,
    now()                                                  AS snapshot_at
FROM routify.gateway_config gc
ORDER BY gc.updated_at DESC;

COMMENT ON VIEW routify.v_gateway_config_audit IS
    'Gateway configuration snapshot. stale_config = true flags sections not updated in 90+ days.';

-- =============================================================================
-- OPERATIONAL PROCEDURES
-- =============================================================================

-------------------------------------------------------------------------------
-- PROCEDURE: routify.purge_published_outbox
-- Purpose  : Delete PUBLISHED outbox entries older than retention_days (default 7).
--            Safe to run daily — PUBLISHED rows are the completed work log.
--            Runs in a single DELETE to avoid long-running transactions.
--
-- Usage    : CALL routify.purge_published_outbox();          -- default 7 days
--            CALL routify.purge_published_outbox(14);        -- custom retention
-------------------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE routify.purge_published_outbox(
    retention_days  INTEGER DEFAULT 7
)
LANGUAGE plpgsql
AS $$
DECLARE
    v_cutoff    TIMESTAMPTZ := now() - (retention_days || ' days')::INTERVAL;
    v_deleted   INTEGER;
BEGIN
    RAISE NOTICE 'purge_published_outbox: deleting PUBLISHED entries older than % (cutoff=%)',
                 retention_days || ' days', v_cutoff;

    DELETE FROM routify.outbox_event
    WHERE  status      = 'PUBLISHED'
      AND  published_at < v_cutoff;

    GET DIAGNOSTICS v_deleted = ROW_COUNT;

    RAISE NOTICE 'purge_published_outbox: deleted % rows', v_deleted;
END;
$$;

COMMENT ON PROCEDURE routify.purge_published_outbox(INTEGER) IS
    'Deletes PUBLISHED outbox entries older than retention_days (default 7). '
    'Safe to call daily from a cron job or ops script. '
    'Does NOT touch PENDING or FAILED entries.';

-------------------------------------------------------------------------------
-- PROCEDURE: routify.reconcile_filter_usage
-- Purpose  : Recompute filter_definition.usage_count from the route_filter join
--            table. Fixes any drift caused by bugs, direct DB edits, or a missed
--            decrement during bulk route deletions.
--
-- Algorithm: Single UPDATE with a correlated subquery — runs in one transaction.
--            Acquires row-level locks on filter_definition rows only; does not
--            block route reads.
--
-- Usage    : CALL routify.reconcile_filter_usage();
--            CALL routify.reconcile_filter_usage('acme-tenant-uuid');
-------------------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE routify.reconcile_filter_usage(
    p_tenant_id  UUID DEFAULT NULL     -- NULL = reconcile all tenants
)
LANGUAGE plpgsql
AS $$
DECLARE
    v_updated   INTEGER;
    v_drifted   INTEGER;
BEGIN
    RAISE NOTICE 'reconcile_filter_usage: starting (tenant_id=%)', p_tenant_id;

    -- Count definitions where usage_count disagrees with live join-table count
    SELECT COUNT(*) INTO v_drifted
    FROM routify.filter_definition fd
    LEFT JOIN (
        SELECT filter_definition_id, COUNT(*) AS actual_count
        FROM   routify.route_filter
        GROUP BY filter_definition_id
    ) rc ON rc.filter_definition_id = fd.id
    WHERE (p_tenant_id IS NULL OR fd.tenant_id = p_tenant_id)
      AND fd.usage_count IS DISTINCT FROM COALESCE(rc.actual_count, 0);

    IF v_drifted = 0 THEN
        RAISE NOTICE 'reconcile_filter_usage: no drift detected — nothing to do';
        RETURN;
    END IF;

    RAISE NOTICE 'reconcile_filter_usage: % definition(s) have drifted usage_count — reconciling',
                 v_drifted;

    UPDATE routify.filter_definition fd
    SET    usage_count = COALESCE(rc.actual_count, 0),
           updated_at  = now()
    FROM (
        SELECT filter_definition_id, COUNT(*) AS actual_count
        FROM   routify.route_filter
        GROUP BY filter_definition_id
    ) rc
    WHERE  fd.id = rc.filter_definition_id
      AND  (p_tenant_id IS NULL OR fd.tenant_id = p_tenant_id)
      AND  fd.usage_count IS DISTINCT FROM rc.actual_count;

    -- Also zero-out definitions that have no route_filter rows at all
    UPDATE routify.filter_definition fd
    SET    usage_count = 0,
           updated_at  = now()
    WHERE  fd.usage_count <> 0
      AND  (p_tenant_id IS NULL OR fd.tenant_id = p_tenant_id)
      AND  NOT EXISTS (
               SELECT 1 FROM routify.route_filter rf
               WHERE rf.filter_definition_id = fd.id
           );

    GET DIAGNOSTICS v_updated = ROW_COUNT;

    RAISE NOTICE 'reconcile_filter_usage: reconciled % filter definition(s)', v_drifted;
END;
$$;

COMMENT ON PROCEDURE routify.reconcile_filter_usage(UUID) IS
    'Recomputes filter_definition.usage_count from the route_filter join table. '
    'Call after bulk data migrations, direct DB edits, or any suspected drift. '
    'Pass a tenant UUID to scope reconciliation to a single tenant, or NULL for all.';

-------------------------------------------------------------------------------
-- PROCEDURE: routify.archive_soft_deleted_routes
-- Purpose  : Hard-delete ARCHIVED routes (and their route_filter rows via CASCADE)
--            that have been soft-deleted for longer than older_than_days.
--
-- Safety guards:
--   • Refuses to delete ACTIVE routes (status check).
--   • Runs in a single transaction; rolls back on any error.
--   • Prints a dry-run count before deleting when p_dry_run = true.
--   • Decrements filter usage_count for every detached filter atomically.
--
-- Usage    : CALL routify.archive_soft_deleted_routes();             -- default 90 days, live
--            CALL routify.archive_soft_deleted_routes(30, true);     -- 30 days, dry-run
--            CALL routify.archive_soft_deleted_routes(90, false, 'tenant-uuid');
-------------------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE routify.archive_soft_deleted_routes(
    older_than_days  INTEGER DEFAULT 90,
    p_dry_run        BOOLEAN DEFAULT false,
    p_tenant_id      UUID    DEFAULT NULL
)
LANGUAGE plpgsql
AS $$
DECLARE
    v_cutoff        TIMESTAMPTZ := now() - (older_than_days || ' days')::INTERVAL;
    v_route_count   INTEGER;
    v_filter_count  INTEGER;
    rec             RECORD;
BEGIN
    RAISE NOTICE 'archive_soft_deleted_routes: mode=% older_than_days=% cutoff=%',
                 CASE WHEN p_dry_run THEN 'DRY-RUN' ELSE 'LIVE' END,
                 older_than_days, v_cutoff;

    -- Count eligible ARCHIVED routes
    SELECT COUNT(*) INTO v_route_count
    FROM   routify.route
    WHERE  status = 'ARCHIVED'
      AND  updated_at < v_cutoff
      AND  (p_tenant_id IS NULL OR tenant_id = p_tenant_id);

    IF v_route_count = 0 THEN
        RAISE NOTICE 'archive_soft_deleted_routes: no eligible routes found';
        RETURN;
    END IF;

    -- Count associated route_filter rows
    SELECT COUNT(*) INTO v_filter_count
    FROM   routify.route_filter rf
    JOIN   routify.route r ON r.id = rf.route_id
    WHERE  r.status = 'ARCHIVED'
      AND  r.updated_at < v_cutoff
      AND  (p_tenant_id IS NULL OR r.tenant_id = p_tenant_id);

    RAISE NOTICE 'archive_soft_deleted_routes: % route(s) with % filter attachment(s) eligible for deletion',
                 v_route_count, v_filter_count;

    IF p_dry_run THEN
        RAISE NOTICE 'archive_soft_deleted_routes: DRY-RUN — no changes made';
        RETURN;
    END IF;

    -- Atomically decrement usage_count for every filter that will be detached
    -- before CASCADE deletes the route_filter rows.
    FOR rec IN
        SELECT DISTINCT rf.filter_definition_id
        FROM   routify.route_filter rf
        JOIN   routify.route r ON r.id = rf.route_id
        WHERE  r.status = 'ARCHIVED'
          AND  r.updated_at < v_cutoff
          AND  (p_tenant_id IS NULL OR r.tenant_id = p_tenant_id)
    LOOP
        PERFORM routify.decrement_filter_usage(rec.filter_definition_id);
    END LOOP;

    -- Hard-delete ARCHIVED routes; route_filter rows cascade automatically
    DELETE FROM routify.route
    WHERE  status = 'ARCHIVED'
      AND  updated_at < v_cutoff
      AND  (p_tenant_id IS NULL OR tenant_id = p_tenant_id);

    RAISE NOTICE 'archive_soft_deleted_routes: deleted % route(s) and % filter attachment(s)',
                 v_route_count, v_filter_count;
END;
$$;

COMMENT ON PROCEDURE routify.archive_soft_deleted_routes(INTEGER, BOOLEAN, UUID) IS
    'Hard-deletes ARCHIVED routes older than older_than_days (default 90). '
    'Set p_dry_run=true to see counts without making changes. '
    'Decrements filter usage_count atomically before the CASCADE delete. '
    'Scoped to a single tenant when p_tenant_id is provided.';

-------------------------------------------------------------------------------
-- PROCEDURE: routify.reset_stalled_outbox_locks
-- Purpose  : Clear locked_until on outbox rows whose lock has expired but
--            status is still PENDING (poller died mid-flight).
--            Safe to call from a health-check cron; idempotent.
--
-- Usage    : CALL routify.reset_stalled_outbox_locks();
-------------------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE routify.reset_stalled_outbox_locks()
LANGUAGE plpgsql
AS $$
DECLARE
    v_cleared INTEGER;
BEGIN
    UPDATE routify.outbox_event
    SET    locked_until = NULL
    WHERE  status       = 'PENDING'
      AND  locked_until IS NOT NULL
      AND  locked_until < now();   -- lock has expired

    GET DIAGNOSTICS v_cleared = ROW_COUNT;

    IF v_cleared > 0 THEN
        RAISE NOTICE 'reset_stalled_outbox_locks: cleared % expired locks', v_cleared;
    END IF;
END;
$$;

COMMENT ON PROCEDURE routify.reset_stalled_outbox_locks() IS
    'Clears expired locked_until values on PENDING outbox rows. '
    'Run every 60 s from a cron or the OutboxPoller health-check to recover '
    'from poller crashes mid-flight without waiting for the lock TTL to expire naturally.';

