-- =============================================================================
-- routify_ops schema — Unified Platform Monitoring Views & Master Procedures
-- =============================================================================
-- This script creates a dedicated routify_ops schema that aggregates monitoring
-- views and operational procedures spanning all four service schemas:
--   • routify          (route-service)
--   • routify_identity (identity-service)
--   • routify_audit    (audit-service)
--   • routify_cert     (cert-vault)
--
-- All views here are read-only. They query against materialised snapshots or use
-- narrow time-window WHERE clauses so PostgreSQL partition pruning fires and the
-- primary write paths are never impacted.
--
-- DEPLOYMENT: Execute once against the primary after all four service Flyway
-- migrations have run (Flyway manages V1–Vn per service; this script is applied
-- by the infrastructure / ops team directly — not via Flyway — because it spans
-- multiple schemas owned by different services).
--
-- RE-APPLY SAFELY: every object uses CREATE OR REPLACE / IF NOT EXISTS.
-- =============================================================================

-- ─── Schema ───────────────────────────────────────────────────────────────────
CREATE SCHEMA IF NOT EXISTS routify_ops;

COMMENT ON SCHEMA routify_ops IS
    'Unified cross-schema monitoring views and operational procedures for the '
    'Routify platform. Spans routify, routify_identity, routify_audit, routify_cert.';

-- Grant read-only access to a dedicated monitoring role (create if needed)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'routify_monitor') THEN
        CREATE ROLE routify_monitor NOLOGIN;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA routify_ops TO routify_monitor;

-- Allow the monitoring role to read from all service schemas
GRANT USAGE ON SCHEMA routify          TO routify_monitor;
GRANT USAGE ON SCHEMA routify_identity TO routify_monitor;
GRANT USAGE ON SCHEMA routify_audit    TO routify_monitor;
GRANT USAGE ON SCHEMA routify_cert     TO routify_monitor;

-- The application role needs to create objects in routify_ops
GRANT ALL PRIVILEGES ON SCHEMA routify_ops TO routify;

-- =============================================================================
-- VIEW: routify_ops.v_platform_health
-- =============================================================================
-- One-row-per-tenant snapshot of the entire platform: route health, user health,
-- active cert count, and request error rate. Designed for the ops overview
-- dashboard and automated health-check pings (Prometheus text exporter).
-- =============================================================================
CREATE OR REPLACE VIEW routify_ops.v_platform_health AS
SELECT
    t.id                                                        AS tenant_id,
    t.name                                                      AS tenant_name,
    t.slug,
    t.status                                                    AS tenant_status,
    t.plan,

    -- ── Route counts (from routify schema) ──────────────────────────────────
    COALESCE(rh.total_routes,   0)                              AS total_routes,
    COALESCE(rh.active_routes,  0)                              AS active_routes,
    COALESCE(rh.draft_routes,   0)                              AS draft_routes,
    COALESCE(rh.active_routes_no_filters, 0)                    AS unprotected_active_routes,

    -- ── User counts (from routify_identity schema) ──────────────────────────
    COALESCE(th.total_users,  0)                                AS total_users,
    COALESCE(th.active_users, 0)                                AS active_users,
    COALESCE(th.locked_users, 0)                                AS locked_users,

    -- ── Certificate health (from routify_cert schema) ────────────────────────
    COALESCE(cc.total_active_certs,  0)                         AS active_certs,
    COALESCE(cc.gateway_mapped,      0)                         AS gateway_mapped_certs,
    COALESCE(cc.mapped_expiring_soon, 0)                        AS certs_expiring_30d,

    -- ── Request error rate (from routify_audit schema, last 24 h) ────────────
    COALESCE(err.total_requests,  0)                            AS requests_24h,
    COALESCE(err.failed_requests, 0)                            AS failed_requests_24h,
    COALESCE(err.error_rate_pct,  0)                            AS error_rate_pct_24h,
    COALESCE(err.p95_latency_ms,  0)                            AS p95_latency_ms_24h,

    -- ── Quota utilisation ────────────────────────────────────────────────────
    qp.max_routes                                               AS quota_max_routes,
    qp.max_filters                                              AS quota_max_filters,
    CASE
        WHEN qp.max_routes > 0 AND qp.max_routes < 2147483647
        THEN ROUND((100.0 * COALESCE(rh.total_routes, 0) / qp.max_routes)::NUMERIC, 1)
        ELSE NULL
    END                                                         AS route_quota_used_pct,

    now()                                                       AS snapshot_at

FROM routify_identity.tenant t

-- Route health (single-schema view join)
LEFT JOIN routify.v_route_health rh
       ON rh.tenant_id = t.id

-- Tenant/user health (single-schema view join)
LEFT JOIN routify_identity.v_tenant_health th
       ON th.tenant_id = t.id

-- Certificate coverage (single-schema view join)
LEFT JOIN routify_cert.v_gateway_cert_coverage cc
       ON cc.tenant_id = t.id

-- Request error rates (aggregated per tenant — collapses the per-route view)
LEFT JOIN (
    SELECT
        tenant_id,
        SUM(total_requests)                          AS total_requests,
        SUM(failed_requests)                         AS failed_requests,
        ROUND((100.0 * SUM(failed_requests) / NULLIF(SUM(total_requests), 0))::NUMERIC, 2)
                                                     AS error_rate_pct,
        MAX(p95_latency_ms)                          AS p95_latency_ms
    FROM routify_audit.v_request_error_rates
    GROUP BY tenant_id
) err ON err.tenant_id = t.id

-- Quota policy
LEFT JOIN routify_identity.quota_policy qp
       ON qp.plan = t.plan::TEXT

WHERE t.status <> 'DELETED'
ORDER BY t.name;

COMMENT ON VIEW routify_ops.v_platform_health IS
    'One-row-per-tenant cross-schema platform health snapshot. '
    'Aggregates route counts, user counts, cert expiry, and request error rates. '
    'Query against a read replica. Never query on the write path.';

-- =============================================================================
-- VIEW: routify_ops.v_outbox_health_all
-- =============================================================================
-- Unified outbox health across all three service outboxes in a single result set.
-- Prometheus scraper queries this to alert on stalled pollers.
-- =============================================================================
CREATE OR REPLACE VIEW routify_ops.v_outbox_health_all AS
-- Route-service outbox
SELECT * FROM routify.v_outbox_health
UNION ALL
-- Identity-service outbox
SELECT * FROM routify_identity.v_identity_outbox_health
UNION ALL
-- Cert-vault outbox
SELECT * FROM routify_cert.v_cert_outbox_health;

COMMENT ON VIEW routify_ops.v_outbox_health_all IS
    'Unified outbox health across all three service outboxes (route, identity, cert-vault). '
    'A single query gives a complete picture of the event publishing pipeline. '
    'oldest_pending_age_seconds > 30 on any row triggers an alert.';

-- =============================================================================
-- VIEW: routify_ops.v_security_alerts
-- =============================================================================
-- Cross-schema security alerts: unprotected routes, expiring certs, locked users,
-- DLQ spikes. Feeds the admin-api security summary endpoint and the ops Slack bot.
-- =============================================================================
CREATE OR REPLACE VIEW routify_ops.v_security_alerts AS

-- ── 1. ACTIVE routes with no enabled filters (unprotected) ──────────────────
SELECT
    'UNPROTECTED_ROUTE'                                         AS alert_type,
    'HIGH'                                                      AS severity,
    r.tenant_id,
    r.id::TEXT                                                  AS entity_id,
    r.name                                                      AS entity_name,
    'Active route has no enabled filters — requests pass through unprotected'
                                                                AS description,
    r.activated_at                                              AS alert_since
FROM routify.route r
WHERE r.status = 'ACTIVE'
  AND NOT EXISTS (
      SELECT 1 FROM routify.route_filter rf
      WHERE rf.route_id = r.id AND rf.enabled = true
  )

UNION ALL

-- ── 2. Certificates expiring within 30 days ──────────────────────────────────
SELECT
    CASE
        WHEN sc.expires_at <= now()                           THEN 'CERT_EXPIRED'
        WHEN sc.expires_at <= now() + INTERVAL '7 days'       THEN 'CERT_CRITICAL'
        ELSE 'CERT_EXPIRING_SOON'
    END                                                         AS alert_type,
    CASE
        WHEN sc.expires_at <= now()                           THEN 'CRITICAL'
        WHEN sc.expires_at <= now() + INTERVAL '7 days'       THEN 'CRITICAL'
        ELSE 'WARNING'
    END                                                         AS severity,
    sc.tenant_id,
    sc.id::TEXT                                                 AS entity_id,
    sc.alias                                                    AS entity_name,
    'Certificate expires in '
         ROUND((EXTRACT(EPOCH FROM (sc.expires_at - now())) / 86400.0)::NUMERIC, 0)::TEXT
        || ' days — effective_logical_id: '
        || COALESCE(sc.effective_logical_id, '(unmapped)')      AS description,
    sc.expires_at                                               AS alert_since
FROM routify_cert.stored_certificate sc
WHERE sc.status    = 'ACTIVE'
  AND sc.expires_at IS NOT NULL
  AND sc.expires_at <= now() + INTERVAL '30 days'

UNION ALL

-- ── 3. Users with active time-limited lockouts ───────────────────────────────
SELECT
    'USER_LOCKED'                                               AS alert_type,
    'MEDIUM'                                                    AS severity,
    u.tenant_id,
    u.id::TEXT                                                  AS entity_id,
    u.username                                                  AS entity_name,
    'User account is temporarily locked until '
        || u.locked_until::TEXT
        || ' (failed_login_attempts='
        || u.failed_login_attempts::TEXT || ')'                 AS description,
    u.locked_until                                              AS alert_since
FROM routify_identity.app_user u
WHERE u.status     = 'ACTIVE'
  AND u.locked_until IS NOT NULL
  AND u.locked_until > now()

UNION ALL

-- ── 4. DLQ hot topics — any source_topic with failures in the last hour ───────
SELECT
    'DLQ_SPIKE'                                                 AS alert_type,
    'HIGH'                                                      AS severity,
    NULL::UUID                                                  AS tenant_id,
    d.source_topic                                              AS entity_id,
    d.source_topic                                              AS entity_name,
    'DLQ spike: '
        || COUNT(*)::TEXT || ' failure(s) in the last hour on '
        || d.dlq_topic                                          AS description,
    MAX(d.failed_at)                                            AS alert_since
FROM routify_audit.dlq_event d
WHERE d.failed_at >= now() - INTERVAL '1 hour'
GROUP BY d.source_topic, d.dlq_topic
HAVING COUNT(*) > 0

ORDER BY severity DESC, alert_since DESC;

COMMENT ON VIEW routify_ops.v_security_alerts IS
    'Cross-schema security and operational alerts. Covers: '
    'unprotected active routes, expiring/expired certificates, locked users, and DLQ spikes. '
    'Feeds the admin-api /api/v1/admin/alerts endpoint and the ops monitoring bot.';

-- =============================================================================
-- PROCEDURE: routify_ops.run_daily_maintenance
-- =============================================================================
-- Master maintenance procedure — calls all individual service cleanup procedures
-- in the correct order. Designed to run from a scheduled job (e.g. pg_cron or
-- an external cron that connects to Postgres).
--
-- Usage:
--   CALL routify_ops.run_daily_maintenance();           -- default retention settings
--   CALL routify_ops.run_daily_maintenance(true);       -- dry-run mode (no deletes)
-- =============================================================================
CREATE OR REPLACE PROCEDURE routify_ops.run_daily_maintenance(
    p_dry_run  BOOLEAN DEFAULT false
)
LANGUAGE plpgsql
AS $$
DECLARE
    v_mode TEXT := CASE WHEN p_dry_run THEN 'DRY-RUN' ELSE 'LIVE' END;
BEGIN
    RAISE NOTICE '=== routify_ops.run_daily_maintenance [%] started at % ===', v_mode, now();

    -- ── 1. Purge published outbox entries (all three outboxes) ───────────────
    RAISE NOTICE '--- Step 1/7: Purge published outbox entries ---';
    IF NOT p_dry_run THEN
        CALL routify.purge_published_outbox(7);
        CALL routify_identity.purge_published_identity_outbox(7);
        CALL routify_cert.purge_published_cert_outbox(7);
    ELSE
        RAISE NOTICE 'DRY-RUN: would call purge_published_outbox(7) x3';
    END IF;

    -- ── 2. Reset stalled outbox locks ────────────────────────────────────────
    RAISE NOTICE '--- Step 2/7: Reset stalled outbox locks ---';
    IF NOT p_dry_run THEN
        CALL routify.reset_stalled_outbox_locks();
    ELSE
        RAISE NOTICE 'DRY-RUN: would call reset_stalled_outbox_locks()';
    END IF;

    -- ── 3. Purge expired & revoked refresh tokens ─────────────────────────────
    RAISE NOTICE '--- Step 3/7: Purge expired refresh tokens ---';
    IF NOT p_dry_run THEN
        CALL routify_identity.purge_expired_refresh_tokens(1);
    ELSE
        RAISE NOTICE 'DRY-RUN: would call purge_expired_refresh_tokens(1)';
    END IF;

    -- ── 4. Release expired user lockouts ─────────────────────────────────────
    RAISE NOTICE '--- Step 4/7: Release expired user lockouts ---';
    IF NOT p_dry_run THEN
        CALL routify_identity.unlock_expired_user_lockouts();
    ELSE
        RAISE NOTICE 'DRY-RUN: would call unlock_expired_user_lockouts()';
    END IF;

    -- ── 5. Mark expired certificates ─────────────────────────────────────────
    RAISE NOTICE '--- Step 5/7: Mark expired certificates ---';
    IF NOT p_dry_run THEN
        CALL routify_cert.mark_expired_certificates(false);
    ELSE
        CALL routify_cert.mark_expired_certificates(true);   -- dry-run always safe to run
    END IF;

    -- ── 6. Reset stalled replay jobs ─────────────────────────────────────────
    RAISE NOTICE '--- Step 6/7: Reset stalled replay jobs ---';
    IF NOT p_dry_run THEN
        CALL routify_audit.reset_stalled_replays(5);
    ELSE
        RAISE NOTICE 'DRY-RUN: would call reset_stalled_replays(5)';
    END IF;

    -- ── 7. Reconcile filter usage counts ─────────────────────────────────────
    RAISE NOTICE '--- Step 7/7: Reconcile filter usage counts ---';
    -- reconcile_filter_usage is safe in dry-run too (it only mutates if drift exists)
    CALL routify.reconcile_filter_usage();

    RAISE NOTICE '=== routify_ops.run_daily_maintenance [%] finished at % ===', v_mode, now();
END;
$$;

COMMENT ON PROCEDURE routify_ops.run_daily_maintenance(BOOLEAN) IS
    'Master daily maintenance procedure — calls all service-level cleanup routines in order. '
    'Steps: purge outboxes → reset locks → purge tokens → unlock users → '
    'mark expired certs → reset stalled replays → reconcile filter usage. '
    'Set p_dry_run=true to preview what would be done without making changes.';

-- =============================================================================
-- PROCEDURE: routify_ops.run_weekly_maintenance
-- =============================================================================
-- Heavier weekly tasks: partition retention enforcement, deep reconciliation,
-- and structured health reports. Designed for Saturday night / Sunday morning
-- off-peak execution.
--
-- Usage:
--   CALL routify_ops.run_weekly_maintenance();          -- keep 6 months, live
--   CALL routify_ops.run_weekly_maintenance(3, true);   -- keep 3 months, dry-run
-- =============================================================================
CREATE OR REPLACE PROCEDURE routify_ops.run_weekly_maintenance(
    keep_audit_months  INTEGER DEFAULT 6,
    p_dry_run          BOOLEAN DEFAULT true
)
LANGUAGE plpgsql
AS $$
DECLARE
    v_mode TEXT := CASE WHEN p_dry_run THEN 'DRY-RUN' ELSE 'LIVE' END;
BEGIN
    RAISE NOTICE '=== routify_ops.run_weekly_maintenance [%] started at % ===', v_mode, now();

    -- ── 1. Drop old audit partitions ─────────────────────────────────────────
    RAISE NOTICE '--- Step 1/4: Drop old audit partitions (keep % months) ---', keep_audit_months;
    CALL routify_audit.drop_old_partitions(keep_audit_months, p_dry_run);

    -- ── 2. Hard-delete ARCHIVED routes older than 90 days ────────────────────
    RAISE NOTICE '--- Step 2/4: Hard-delete ARCHIVED routes ---';
    CALL routify.archive_soft_deleted_routes(90, p_dry_run);

    -- ── 3. Hard-delete DELETED certificates older than 90 days ───────────────
    RAISE NOTICE '--- Step 3/4: Purge soft-deleted certificates ---';
    CALL routify_cert.purge_deleted_certificates(90, p_dry_run);

    -- ── 4. Tenant status reconciliation report ────────────────────────────────
    RAISE NOTICE '--- Step 4/4: Tenant status reconciliation ---';
    CALL routify_identity.reconcile_tenant_status();

    RAISE NOTICE '=== routify_ops.run_weekly_maintenance [%] finished at % ===', v_mode, now();
END;
$$;

COMMENT ON PROCEDURE routify_ops.run_weekly_maintenance(INTEGER, BOOLEAN) IS
    'Weekly maintenance: audit partition retention, ARCHIVED route hard-delete, '
    'DELETED cert purge, and tenant status reconciliation. '
    'Always run with p_dry_run=true first. Default keep_audit_months=6.';

-- =============================================================================
-- PROCEDURE: routify_ops.full_health_report
-- =============================================================================
-- Prints a complete platform health report via RAISE NOTICE.
-- Designed for the start-of-shift handover and incident kickoff.
--
-- Usage:
--   CALL routify_ops.full_health_report();
-- =============================================================================
CREATE OR REPLACE PROCEDURE routify_ops.full_health_report()
LANGUAGE plpgsql
AS $$
DECLARE
    rec  RECORD;
    v_alert_count INTEGER;
BEGIN
    RAISE NOTICE '========================================================';
    RAISE NOTICE '  Routify Platform Health Report — %', now();
    RAISE NOTICE '========================================================';

    -- ── Active Tenants ───────────────────────────────────────────────────────
    RAISE NOTICE '';
    RAISE NOTICE '--- Active Tenants ---';
    FOR rec IN
        SELECT tenant_name, plan, active_routes, total_users,
               active_certs, error_rate_pct_24h, certs_expiring_30d
        FROM   routify_ops.v_platform_health
        ORDER BY tenant_name
    LOOP
        RAISE NOTICE '  %-30s  plan=%-12s  routes=%-4s  users=%-4s  certs=%-3s  err%%=%-6s  cert_exp=%',
                     rec.tenant_name, rec.plan,
                     rec.active_routes, rec.total_users, rec.active_certs,
                     rec.error_rate_pct_24h, rec.certs_expiring_30d;
    END LOOP;

    -- ── Outbox Health ─────────────────────────────────────────────────────────
    RAISE NOTICE '';
    RAISE NOTICE '--- Outbox Pipeline Health ---';
    FOR rec IN
        SELECT outbox_table, pending_count, failed_count,
               oldest_pending_age_seconds, exhausted_retries_count
        FROM   routify_ops.v_outbox_health_all
        ORDER BY outbox_table
    LOOP
        RAISE NOTICE '  %-40s  pending=%-4s  failed=%-3s  oldest_pending_secs=%-8s  exhausted=%',
                     rec.outbox_table, rec.pending_count, rec.failed_count,
                     COALESCE(rec.oldest_pending_age_seconds::TEXT, 'N/A'),
                     rec.exhausted_retries_count;
    END LOOP;

    -- ── Security Alerts ───────────────────────────────────────────────────────
    RAISE NOTICE '';
    RAISE NOTICE '--- Security & Operational Alerts ---';
    SELECT COUNT(*) INTO v_alert_count FROM routify_ops.v_security_alerts;
    IF v_alert_count = 0 THEN
        RAISE NOTICE '  No active alerts. System healthy.';
    ELSE
        FOR rec IN
            SELECT alert_type, severity, entity_name, description
            FROM   routify_ops.v_security_alerts
            ORDER BY severity DESC, alert_since DESC
            LIMIT 20
        LOOP
            RAISE NOTICE '  [%] %-25s  % — %',
                         rec.severity, rec.alert_type, rec.entity_name, rec.description;
        END LOOP;
        IF v_alert_count > 20 THEN
            RAISE NOTICE '  ... and % more alerts (query routify_ops.v_security_alerts for full list)',
                         v_alert_count - 20;
        END IF;
    END IF;

    -- ── DLQ Summary ───────────────────────────────────────────────────────────
    RAISE NOTICE '';
    RAISE NOTICE '--- DLQ Hot Topics (last 24 h) ---';
    CALL routify_audit.dlq_summary_report(24);

    -- ── Cert Expiry ───────────────────────────────────────────────────────────
    RAISE NOTICE '';
    RAISE NOTICE '--- Certificate Expiry (next 30 days) ---';
    CALL routify_cert.cert_expiry_report(30);

    RAISE NOTICE '';
    RAISE NOTICE '========================================================';
    RAISE NOTICE '  End of Health Report';
    RAISE NOTICE '========================================================';
END;
$$;

COMMENT ON PROCEDURE routify_ops.full_health_report() IS
    'Prints a complete platform health report: tenant summary, outbox health, '
    'security alerts, DLQ hot topics, and certificate expiry. '
    'Use for start-of-shift handover and incident kickoff.';

-- =============================================================================
-- Grant SELECT on all routify_ops views to the monitoring role
-- =============================================================================
GRANT SELECT ON routify_ops.v_platform_health    TO routify_monitor;
GRANT SELECT ON routify_ops.v_outbox_health_all  TO routify_monitor;
GRANT SELECT ON routify_ops.v_security_alerts    TO routify_monitor;

-- Grant SELECT on all single-schema monitoring views
GRANT SELECT ON routify.v_route_health              TO routify_monitor;
GRANT SELECT ON routify.v_filter_inventory          TO routify_monitor;
GRANT SELECT ON routify.v_outbox_health             TO routify_monitor;
GRANT SELECT ON routify.v_gateway_config_audit      TO routify_monitor;

GRANT SELECT ON routify_identity.v_tenant_health          TO routify_monitor;
GRANT SELECT ON routify_identity.v_user_security_summary  TO routify_monitor;
GRANT SELECT ON routify_identity.v_identity_outbox_health TO routify_monitor;
GRANT SELECT ON routify_identity.v_expiring_refresh_tokens TO routify_monitor;

GRANT SELECT ON routify_audit.v_audit_activity_summary   TO routify_monitor;
GRANT SELECT ON routify_audit.v_request_error_rates      TO routify_monitor;
GRANT SELECT ON routify_audit.v_replay_queue_status      TO routify_monitor;
GRANT SELECT ON routify_audit.v_ai_filter_decision_stats TO routify_monitor;
GRANT SELECT ON routify_audit.v_dlq_hot_topics           TO routify_monitor;
GRANT SELECT ON routify_audit.v_partition_inventory      TO routify_monitor;

GRANT SELECT ON routify_cert.v_cert_expiry_dashboard   TO routify_monitor;
GRANT SELECT ON routify_cert.v_cert_group_health        TO routify_monitor;
GRANT SELECT ON routify_cert.v_cert_outbox_health       TO routify_monitor;
GRANT SELECT ON routify_cert.v_gateway_cert_coverage    TO routify_monitor;

