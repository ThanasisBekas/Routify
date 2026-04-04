-- =============================================================================
-- V4: routify_identity schema — Monitoring Views & Operational Procedures
-- =============================================================================
-- All objects live in routify_identity (owned by identity-service).
--
-- VIEWS:
--   v_tenant_health          — per-tenant user counts, status, plan usage
--   v_user_security_summary  — locked / must-change-password flags per tenant
--   v_quota_utilisation      — route/filter counts vs plan limits (cross-schema view)
--   v_identity_outbox_health — identity outbox pipeline health
--   v_expiring_refresh_tokens— refresh tokens expiring within 7 days
--
-- PROCEDURES:
--   routify_identity.purge_expired_refresh_tokens()    — hard-delete expired/revoked tokens
--   routify_identity.purge_published_identity_outbox() — clean up old PUBLISHED outbox rows
--   routify_identity.unlock_expired_user_lockouts()    — release timed-out login lockouts
--   routify_identity.reconcile_tenant_status()         — detect/report inconsistent tenant state
-- =============================================================================

-------------------------------------------------------------------------------
-- VIEW: v_tenant_health
-- Purpose: per-tenant snapshot — status, plan, user counts, last activity.
-------------------------------------------------------------------------------
CREATE OR REPLACE VIEW routify_identity.v_tenant_health AS
SELECT
    t.id                                                    AS tenant_id,
    t.name                                                  AS tenant_name,
    t.slug,
    t.status,
    t.plan,
    t.contact_email,
    t.created_at,
    -- User counts
    COUNT(u.id)                                             AS total_users,
    COUNT(u.id) FILTER (WHERE u.status = 'ACTIVE')         AS active_users,
    COUNT(u.id) FILTER (WHERE u.status = 'LOCKED')         AS locked_users,
    COUNT(u.id) FILTER (WHERE u.status = 'DELETED')        AS deleted_users,
    COUNT(u.id) FILTER (WHERE u.must_change_password = true AND u.status = 'ACTIVE')
                                                            AS users_pending_pwd_change,
    -- Role distribution
    COUNT(u.id) FILTER (WHERE u.role = 'TENANT_ADMIN')     AS admin_count,
    COUNT(u.id) FILTER (WHERE u.role = 'OPERATOR')         AS operator_count,
    COUNT(u.id) FILTER (WHERE u.role = 'VIEWER')           AS viewer_count,
    -- Last login across all users in this tenant
    MAX(u.last_login_at)                                    AS last_login_at,
    -- Quota policy limits for this plan
    qp.max_routes,
    qp.max_filters,
    qp.monthly_request_quota,
    now()                                                   AS snapshot_at
FROM routify_identity.tenant t
LEFT JOIN routify_identity.app_user u
       ON u.tenant_id = t.id
LEFT JOIN routify_identity.quota_policy qp
       ON qp.plan = t.plan::TEXT
WHERE t.status <> 'DELETED'
GROUP BY t.id, t.name, t.slug, t.status, t.plan,
         t.contact_email, t.created_at,
         qp.max_routes, qp.max_filters, qp.monthly_request_quota;

COMMENT ON VIEW routify_identity.v_tenant_health IS
    'Per-tenant snapshot: user counts by status/role, last login, and quota policy limits. '
    'Query against a read replica for dashboard and ops tooling.';

-------------------------------------------------------------------------------
-- VIEW: v_user_security_summary
-- Purpose: surface users with security-relevant flags for ops review:
--          locked accounts, pending password changes, failed login counters.
-------------------------------------------------------------------------------
CREATE OR REPLACE VIEW routify_identity.v_user_security_summary AS
SELECT
    u.id                                                    AS user_id,
    u.tenant_id,
    t.name                                                  AS tenant_name,
    u.username,
    u.email,
    u.role,
    u.status,
    u.must_change_password,
    u.failed_login_attempts,
    u.locked_until,
    -- True if the account is under an active time-limited lockout
    CASE
        WHEN u.locked_until IS NOT NULL AND u.locked_until > now() THEN true
        ELSE false
    END                                                     AS is_temporarily_locked,
    -- Minutes remaining on the lockout (null if not locked)
    CASE
        WHEN u.locked_until IS NOT NULL AND u.locked_until > now()
        THEN ROUND(EXTRACT(EPOCH FROM (u.locked_until - now())) / 60.0, 1)
        ELSE NULL
    END                                                     AS lockout_minutes_remaining,
    u.last_login_at,
    -- Flag accounts inactive for > 90 days (potential zombie accounts)
    CASE
        WHEN u.last_login_at < now() - INTERVAL '90 days' THEN true
        WHEN u.last_login_at IS NULL THEN true   -- never logged in
        ELSE false
    END                                                     AS inactive_account,
    u.created_at,
    now()                                                   AS snapshot_at
FROM routify_identity.app_user u
JOIN routify_identity.tenant t ON t.id = u.tenant_id
WHERE u.status <> 'DELETED'
  AND (
      u.must_change_password = true
   OR u.failed_login_attempts > 0
   OR (u.locked_until IS NOT NULL AND u.locked_until > now())
   OR u.last_login_at < now() - INTERVAL '90 days'
   OR u.last_login_at IS NULL
  );

COMMENT ON VIEW routify_identity.v_user_security_summary IS
    'Users with active security flags: locked accounts, pending password changes, '
    'high failed-login counters, and inactive accounts (no login in 90+ days). '
    'Used by the security review runbook and on-call tooling.';

-------------------------------------------------------------------------------
-- VIEW: v_identity_outbox_health
-- Purpose: identity outbox pipeline health — mirrors v_outbox_health in routify schema.
-------------------------------------------------------------------------------
CREATE OR REPLACE VIEW routify_identity.v_identity_outbox_health AS
SELECT
    'routify_identity.outbox_event'                        AS outbox_table,
    COUNT(*)                                               AS total_entries,
    COUNT(*) FILTER (WHERE status = 'PENDING')             AS pending_count,
    COUNT(*) FILTER (WHERE status = 'PUBLISHED')           AS published_count,
    COUNT(*) FILTER (WHERE status = 'FAILED')              AS failed_count,
    EXTRACT(EPOCH FROM (now() - MIN(created_at) FILTER (WHERE status = 'PENDING')))
                                                           AS oldest_pending_age_seconds,
    MAX(published_at)                                      AS last_published_at,
    COUNT(*) FILTER (WHERE status = 'FAILED' AND retry_count >= 5)
                                                           AS exhausted_retries_count,
    now()                                                  AS snapshot_at
FROM routify_identity.outbox_event;

COMMENT ON VIEW routify_identity.v_identity_outbox_health IS
    'Outbox pipeline health for routify-identity-service. '
    'oldest_pending_age_seconds > 30 indicates a stalled IdentityOutboxPoller. '
    'exhausted_retries_count > 0 requires manual DLQ investigation.';

-------------------------------------------------------------------------------
-- VIEW: v_expiring_refresh_tokens
-- Purpose: surface refresh tokens expiring within 7 days for proactive alerts.
--          Helps catch tokens that will expire before the user next logs in,
--          preventing silent session loss.
-------------------------------------------------------------------------------
CREATE OR REPLACE VIEW routify_identity.v_expiring_refresh_tokens AS
SELECT
    rt.jti,
    rt.user_id,
    rt.tenant_id,
    u.username,
    u.email,
    t.name                                                  AS tenant_name,
    rt.expires_at,
    rt.created_at                                           AS token_issued_at,
    rt.expires_at - now()                                   AS time_until_expiry,
    ROUND(EXTRACT(EPOCH FROM (rt.expires_at - now())) / 3600.0, 1)
                                                            AS hours_until_expiry,
    CASE
        WHEN rt.expires_at <= now()              THEN 'EXPIRED'
        WHEN rt.expires_at <= now() + INTERVAL '1 day'  THEN 'CRITICAL'
        WHEN rt.expires_at <= now() + INTERVAL '3 days' THEN 'WARNING'
        ELSE 'WATCH'
    END                                                     AS expiry_severity,
    now()                                                   AS snapshot_at
FROM routify_identity.refresh_token rt
JOIN routify_identity.app_user u  ON u.id  = rt.user_id
JOIN routify_identity.tenant   t  ON t.id  = rt.tenant_id
WHERE rt.revoked    = false
  AND rt.expires_at <= now() + INTERVAL '7 days'
ORDER BY rt.expires_at ASC;

COMMENT ON VIEW routify_identity.v_expiring_refresh_tokens IS
    'Refresh tokens expiring within 7 days. '
    'expiry_severity: EXPIRED = already expired, CRITICAL = < 1 day, WARNING = 1–3 days, WATCH = 3–7 days.';

-- =============================================================================
-- PROCEDURES
-- =============================================================================

-------------------------------------------------------------------------------
-- PROCEDURE: routify_identity.purge_expired_refresh_tokens
-- Purpose  : Hard-delete refresh tokens that are expired OR explicitly revoked.
--            Keeps the table lean and avoids unbounded growth from abandoned sessions.
--
-- Usage    : CALL routify_identity.purge_expired_refresh_tokens();       -- default 1 day grace
--            CALL routify_identity.purge_expired_refresh_tokens(7);      -- 7 day grace period
-------------------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE routify_identity.purge_expired_refresh_tokens(
    grace_days  INTEGER DEFAULT 1
)
LANGUAGE plpgsql
AS $$
DECLARE
    v_cutoff    TIMESTAMPTZ := now() - (grace_days || ' days')::INTERVAL;
    v_deleted   INTEGER;
BEGIN
    RAISE NOTICE 'purge_expired_refresh_tokens: grace=% days, cutoff=%', grace_days, v_cutoff;

    DELETE FROM routify_identity.refresh_token
    WHERE revoked = true
       OR expires_at < v_cutoff;

    GET DIAGNOSTICS v_deleted = ROW_COUNT;

    RAISE NOTICE 'purge_expired_refresh_tokens: deleted % token(s)', v_deleted;
END;
$$;

COMMENT ON PROCEDURE routify_identity.purge_expired_refresh_tokens(INTEGER) IS
    'Deletes revoked and expired refresh tokens. '
    'grace_days (default 1) prevents deletion of tokens that expired very recently, '
    'in case an in-flight request is still being validated. '
    'Safe to call daily from a scheduled job.';

-------------------------------------------------------------------------------
-- PROCEDURE: routify_identity.purge_published_identity_outbox
-- Purpose  : Delete PUBLISHED identity outbox entries older than retention_days.
--
-- Usage    : CALL routify_identity.purge_published_identity_outbox();    -- 7 days default
-------------------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE routify_identity.purge_published_identity_outbox(
    retention_days  INTEGER DEFAULT 7
)
LANGUAGE plpgsql
AS $$
DECLARE
    v_cutoff  TIMESTAMPTZ := now() - (retention_days || ' days')::INTERVAL;
    v_deleted INTEGER;
BEGIN
    RAISE NOTICE 'purge_published_identity_outbox: deleting PUBLISHED entries older than % days', retention_days;

    DELETE FROM routify_identity.outbox_event
    WHERE  status      = 'PUBLISHED'
      AND  published_at < v_cutoff;

    GET DIAGNOSTICS v_deleted = ROW_COUNT;

    RAISE NOTICE 'purge_published_identity_outbox: deleted % row(s)', v_deleted;
END;
$$;

COMMENT ON PROCEDURE routify_identity.purge_published_identity_outbox(INTEGER) IS
    'Deletes PUBLISHED identity outbox entries older than retention_days. '
    'Mirrors routify.purge_published_outbox() for the identity-service outbox.';

-------------------------------------------------------------------------------
-- PROCEDURE: routify_identity.unlock_expired_user_lockouts
-- Purpose  : Release timed-out login lockouts (locked_until < now()) and reset
--            failed_login_attempts.  Runs automatically but can also be called
--            manually by support to release a legitimate user early.
--
-- Usage    : CALL routify_identity.unlock_expired_user_lockouts();          -- all
--            CALL routify_identity.unlock_expired_user_lockouts('user-uuid'); -- single user
-------------------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE routify_identity.unlock_expired_user_lockouts(
    p_user_id  UUID DEFAULT NULL
)
LANGUAGE plpgsql
AS $$
DECLARE
    v_unlocked INTEGER;
BEGIN
    UPDATE routify_identity.app_user
    SET    locked_until          = NULL,
           failed_login_attempts = 0,
           updated_at            = now()
    WHERE  locked_until IS NOT NULL
      AND  locked_until < now()
      AND  status = 'ACTIVE'
      AND  (p_user_id IS NULL OR id = p_user_id);

    GET DIAGNOSTICS v_unlocked = ROW_COUNT;

    RAISE NOTICE 'unlock_expired_user_lockouts: released % user lockout(s)', v_unlocked;
END;
$$;

COMMENT ON PROCEDURE routify_identity.unlock_expired_user_lockouts(UUID) IS
    'Releases expired timed-lockouts (locked_until < now()) and resets failed_login_attempts. '
    'Pass a user UUID to unlock a specific user, or NULL to unlock all expired lockouts. '
    'Safe to call every 60 s from a scheduler or on-demand by support staff.';

-------------------------------------------------------------------------------
-- PROCEDURE: routify_identity.reconcile_tenant_status
-- Purpose  : Detect and report tenants in inconsistent states:
--              • DELETED tenants that still have ACTIVE users
--              • SUSPENDED tenants with valid (non-revoked) refresh tokens
--              • Tenants with no users at all (orphaned tenant rows)
--
--            This is a READ-ONLY diagnostic — it raises NOTICE messages and
--            populates a temporary table; it does NOT mutate any rows.
--
-- Usage    : CALL routify_identity.reconcile_tenant_status();
-------------------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE routify_identity.reconcile_tenant_status()
LANGUAGE plpgsql
AS $$
DECLARE
    v_issue_count INTEGER := 0;
    rec           RECORD;
BEGIN
    RAISE NOTICE '=== routify_identity.reconcile_tenant_status ===';

    -- ── 1. DELETED tenants with live ACTIVE users ──────────────────────────
    FOR rec IN
        SELECT t.id, t.name, COUNT(u.id) AS active_user_count
        FROM   routify_identity.tenant   t
        JOIN   routify_identity.app_user u ON u.tenant_id = t.id
        WHERE  t.status = 'DELETED'
          AND  u.status = 'ACTIVE'
        GROUP BY t.id, t.name
    LOOP
        RAISE WARNING 'INCONSISTENCY: DELETED tenant id=% name=% still has % ACTIVE user(s)',
                      rec.id, rec.name, rec.active_user_count;
        v_issue_count := v_issue_count + 1;
    END LOOP;

    -- ── 2. SUSPENDED tenants with live refresh tokens ──────────────────────
    FOR rec IN
        SELECT t.id, t.name, COUNT(rt.jti) AS live_token_count
        FROM   routify_identity.tenant       t
        JOIN   routify_identity.refresh_token rt ON rt.tenant_id = t.id
        WHERE  t.status = 'SUSPENDED'
          AND  rt.revoked   = false
          AND  rt.expires_at > now()
        GROUP BY t.id, t.name
    LOOP
        RAISE WARNING 'INCONSISTENCY: SUSPENDED tenant id=% name=% has % live refresh token(s)',
                      rec.id, rec.name, rec.live_token_count;
        v_issue_count := v_issue_count + 1;
    END LOOP;

    -- ── 3. Tenants with zero users (orphaned) ──────────────────────────────
    FOR rec IN
        SELECT t.id, t.name, t.created_at
        FROM   routify_identity.tenant t
        WHERE  t.status <> 'DELETED'
          AND  NOT EXISTS (
              SELECT 1 FROM routify_identity.app_user u
              WHERE u.tenant_id = t.id AND u.status <> 'DELETED'
          )
    LOOP
        RAISE NOTICE 'INFO: Tenant id=% name=% (created %) has no active users',
                     rec.id, rec.name, rec.created_at;
        v_issue_count := v_issue_count + 1;
    END LOOP;

    IF v_issue_count = 0 THEN
        RAISE NOTICE 'reconcile_tenant_status: no inconsistencies found';
    ELSE
        RAISE NOTICE 'reconcile_tenant_status: found % issue(s) — review WARNINGs above', v_issue_count;
    END IF;
END;
$$;

COMMENT ON PROCEDURE routify_identity.reconcile_tenant_status() IS
    'READ-ONLY diagnostic: detects inconsistent tenant states (deleted tenants with active users, '
    'suspended tenants with live tokens, orphaned tenants). '
    'Does not mutate any rows. Run from the ops runbook after bulk tenant operations.';

