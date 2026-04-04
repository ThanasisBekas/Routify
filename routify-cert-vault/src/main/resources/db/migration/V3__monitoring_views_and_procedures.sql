-- =============================================================================
-- V3: routify_cert schema — Monitoring Views & Operational Procedures
-- =============================================================================
-- All objects live in routify_cert (owned by cert-vault service).
--
-- VIEWS (read-only, zero write-path impact):
--   v_cert_expiry_dashboard     — certificate expiry status across all tenants
--   v_cert_group_health         — group composition, active member counts, coverage
--   v_cert_outbox_health        — cert-vault outbox pipeline health
--   v_gateway_cert_coverage     — which certs are mapped to the gateway vs orphaned
--
-- PROCEDURES:
--   routify_cert.purge_published_cert_outbox(retention_days)  — clean up old PUBLISHED outbox rows
--   routify_cert.mark_expired_certificates()                  — transition ACTIVE → EXPIRED status
--   routify_cert.purge_deleted_certificates(older_than_days)  — hard-delete soft-deleted certs
--   routify_cert.cert_expiry_report(warning_days)             — structured expiry report via NOTICE
-- =============================================================================

-------------------------------------------------------------------------------
-- VIEW: v_cert_expiry_dashboard
-- Purpose: per-tenant certificate expiry status — the primary operational view
--          for the certificate management dashboard and expiry alerting.
-------------------------------------------------------------------------------
CREATE OR REPLACE VIEW routify_cert.v_cert_expiry_dashboard AS
SELECT
    sc.id                                                       AS cert_id,
    sc.tenant_id,
    sc.alias,
    sc.subject_dn,
    sc.issuer_dn,
    sc.serial_number,
    sc.format,
    sc.status,
    sc.not_before,
    sc.not_after,
    sc.expires_at,
    sc.effective_logical_id,
    sc.group_id,
    cg.alias                                                    AS group_alias,
    cg.logical_id                                               AS group_logical_id,
    sc.member_alias,
    sc.fingerprint_sha256,
    sc.key_algorithm,
    sc.key_size,
    sc.is_ca,
    sc.uploaded_by,
    sc.created_at,
    -- Derived expiry fields
    sc.expires_at - now()                                       AS time_until_expiry,
    ROUND(EXTRACT(EPOCH FROM (sc.expires_at - now())) / 86400.0, 1)
                                                                AS days_until_expiry,
    -- Severity bucket
    CASE
        WHEN sc.status IN ('REVOKED', 'DELETED')              THEN 'INACTIVE'
        WHEN sc.expires_at IS NULL                            THEN 'NO_EXPIRY'
        WHEN sc.expires_at <= now()                           THEN 'EXPIRED'
        WHEN sc.expires_at <= now() + INTERVAL '7 days'       THEN 'CRITICAL'
        WHEN sc.expires_at <= now() + INTERVAL '30 days'      THEN 'WARNING'
        WHEN sc.expires_at <= now() + INTERVAL '90 days'      THEN 'WATCH'
        ELSE                                                       'VALID'
    END                                                         AS expiry_severity,
    -- SANs (DNS + IP)
    sc.san_dns,
    sc.san_ip,
    now()                                                       AS snapshot_at
FROM routify_cert.stored_certificate sc
LEFT JOIN routify_cert.cert_group cg ON cg.id = sc.group_id
WHERE sc.status NOT IN ('DELETED')
ORDER BY
    CASE
        WHEN sc.expires_at IS NULL THEN 99
        ELSE EXTRACT(EPOCH FROM (sc.expires_at - now()))
    END ASC;

COMMENT ON VIEW routify_cert.v_cert_expiry_dashboard IS
    'Certificate expiry status across all tenants. '
    'expiry_severity: EXPIRED/CRITICAL/WARNING/WATCH/VALID/NO_EXPIRY/INACTIVE. '
    'Primary view for the cert management dashboard and expiry alert rules.';

-------------------------------------------------------------------------------
-- VIEW: v_cert_group_health
-- Purpose: group-level summary — member counts, coverage, and whether the group
--          has any active certificate mapped to the gateway.
-------------------------------------------------------------------------------
CREATE OR REPLACE VIEW routify_cert.v_cert_group_health AS
SELECT
    cg.id                                                       AS group_id,
    cg.tenant_id,
    cg.logical_id,
    cg.alias,
    cg.status                                                   AS group_status,
    cg.created_at,
    -- Member counts
    COUNT(sc.id)                                                AS total_members,
    COUNT(sc.id) FILTER (WHERE sc.status = 'ACTIVE')           AS active_members,
    COUNT(sc.id) FILTER (WHERE sc.status = 'REVOKED')          AS revoked_members,
    COUNT(sc.id) FILTER (WHERE sc.status = 'EXPIRED')          AS expired_members,
    -- Gateway mapping
    COUNT(sc.id) FILTER (WHERE sc.effective_logical_id IS NOT NULL AND sc.status = 'ACTIVE')
                                                                AS gateway_mapped_members,
    -- Soonest expiry across active members
    MIN(sc.expires_at) FILTER (WHERE sc.status = 'ACTIVE')     AS soonest_active_expiry,
    ROUND(
        EXTRACT(EPOCH FROM (
            MIN(sc.expires_at) FILTER (WHERE sc.status = 'ACTIVE') - now()
        )) / 86400.0, 1
    )                                                           AS days_until_soonest_expiry,
    -- Health flag: group has no active gateway-mapped cert
    CASE
        WHEN cg.status = 'ARCHIVED' THEN 'ARCHIVED'
        WHEN COUNT(sc.id) FILTER (
                 WHERE sc.status = 'ACTIVE'
                   AND sc.effective_logical_id IS NOT NULL
             ) = 0 THEN 'NO_ACTIVE_MAPPING'
        ELSE 'HEALTHY'
    END                                                         AS group_health,
    now()                                                       AS snapshot_at
FROM routify_cert.cert_group cg
LEFT JOIN routify_cert.stored_certificate sc ON sc.group_id = cg.id
GROUP BY cg.id, cg.tenant_id, cg.logical_id, cg.alias, cg.status, cg.created_at;

COMMENT ON VIEW routify_cert.v_cert_group_health IS
    'Certificate group health: member counts, soonest active expiry, and gateway mapping status. '
    'group_health = NO_ACTIVE_MAPPING means no active cert in the group is bound to the gateway TLS registry.';

-------------------------------------------------------------------------------
-- VIEW: v_cert_outbox_health
-- Purpose: cert-vault outbox pipeline health — mirrors v_outbox_health pattern.
-------------------------------------------------------------------------------
CREATE OR REPLACE VIEW routify_cert.v_cert_outbox_health AS
SELECT
    'routify_cert.cert_outbox_event'                            AS outbox_table,
    COUNT(*)                                                    AS total_entries,
    COUNT(*) FILTER (WHERE status = 'PENDING')                  AS pending_count,
    COUNT(*) FILTER (WHERE status = 'PUBLISHED')                AS published_count,
    COUNT(*) FILTER (WHERE status = 'FAILED')                   AS failed_count,
    EXTRACT(EPOCH FROM (now() - MIN(created_at) FILTER (WHERE status = 'PENDING')))
                                                                AS oldest_pending_age_seconds,
    MAX(published_at)                                           AS last_published_at,
    COUNT(*) FILTER (WHERE status = 'FAILED' AND retry_count >= 5)
                                                                AS exhausted_retries_count,
    now()                                                       AS snapshot_at
FROM routify_cert.cert_outbox_event;

COMMENT ON VIEW routify_cert.v_cert_outbox_health IS
    'Outbox pipeline health for routify-cert-vault. '
    'oldest_pending_age_seconds > 30 indicates a stalled CertOutboxPoller. '
    'exhausted_retries_count > 0 requires manual DLQ investigation.';

-------------------------------------------------------------------------------
-- VIEW: v_gateway_cert_coverage
-- Purpose: which certificates are mapped to the gateway TLS registry vs orphaned
--          (uploaded but not bound to any gateway logical ID).
-------------------------------------------------------------------------------
CREATE OR REPLACE VIEW routify_cert.v_gateway_cert_coverage AS
SELECT
    sc.tenant_id,
    COUNT(*)                                                    AS total_active_certs,
    COUNT(*) FILTER (WHERE sc.effective_logical_id IS NOT NULL)
                                                                AS gateway_mapped,
    COUNT(*) FILTER (WHERE sc.effective_logical_id IS NULL)
                                                                AS unmapped_orphans,
    -- Certs expiring within 30 days that are gateway-mapped (highest urgency)
    COUNT(*) FILTER (
        WHERE sc.effective_logical_id IS NOT NULL
          AND sc.expires_at IS NOT NULL
          AND sc.expires_at <= now() + INTERVAL '30 days'
    )                                                           AS mapped_expiring_soon,
    ROUND(
        100.0 * COUNT(*) FILTER (WHERE sc.effective_logical_id IS NOT NULL)
        / NULLIF(COUNT(*), 0), 1
    )                                                           AS mapping_coverage_pct,
    now()                                                       AS snapshot_at
FROM routify_cert.stored_certificate sc
WHERE sc.status = 'ACTIVE'
GROUP BY sc.tenant_id;

COMMENT ON VIEW routify_cert.v_gateway_cert_coverage IS
    'Per-tenant active cert coverage: how many certs are mapped to the gateway TLS registry. '
    'mapping_coverage_pct < 100 means some certs are uploaded but not bound to any gateway listener. '
    'mapped_expiring_soon > 0 is the highest-priority alert.';

-- =============================================================================
-- PROCEDURES
-- =============================================================================

-------------------------------------------------------------------------------
-- PROCEDURE: routify_cert.purge_published_cert_outbox
-- Purpose  : Delete PUBLISHED cert outbox entries older than retention_days.
--
-- Usage    : CALL routify_cert.purge_published_cert_outbox();      -- default 7 days
-------------------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE routify_cert.purge_published_cert_outbox(
    retention_days  INTEGER DEFAULT 7
)
LANGUAGE plpgsql
AS $$
DECLARE
    v_cutoff  TIMESTAMPTZ := now() - (retention_days || ' days')::INTERVAL;
    v_deleted INTEGER;
BEGIN
    RAISE NOTICE 'purge_published_cert_outbox: deleting PUBLISHED entries older than % days', retention_days;

    DELETE FROM routify_cert.cert_outbox_event
    WHERE  status      = 'PUBLISHED'
      AND  published_at < v_cutoff;

    GET DIAGNOSTICS v_deleted = ROW_COUNT;

    RAISE NOTICE 'purge_published_cert_outbox: deleted % row(s)', v_deleted;
END;
$$;

COMMENT ON PROCEDURE routify_cert.purge_published_cert_outbox(INTEGER) IS
    'Deletes PUBLISHED cert outbox entries older than retention_days (default 7). '
    'Mirrors routify.purge_published_outbox() for the cert-vault outbox.';

-------------------------------------------------------------------------------
-- PROCEDURE: routify_cert.mark_expired_certificates
-- Purpose  : Transition ACTIVE certificates whose not_after < now() to EXPIRED status.
--            The application layer normally does this on first access, but this
--            procedure ensures the DB is consistent even for certs that are never
--            re-queried.  Safe to run nightly.
--
-- Usage    : CALL routify_cert.mark_expired_certificates();
--            CALL routify_cert.mark_expired_certificates(true);  -- dry-run
-------------------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE routify_cert.mark_expired_certificates(
    p_dry_run  BOOLEAN DEFAULT false
)
LANGUAGE plpgsql
AS $$
DECLARE
    v_count   INTEGER;
    v_updated INTEGER;
BEGIN
    SELECT COUNT(*) INTO v_count
    FROM   routify_cert.stored_certificate
    WHERE  status   = 'ACTIVE'
      AND  not_after < now();

    RAISE NOTICE 'mark_expired_certificates: mode=% — % ACTIVE cert(s) with not_after < now()',
                 CASE WHEN p_dry_run THEN 'DRY-RUN' ELSE 'LIVE' END, v_count;

    IF p_dry_run OR v_count = 0 THEN
        RETURN;
    END IF;

    UPDATE routify_cert.stored_certificate
    SET    status     = 'EXPIRED',
           updated_at = now()
    WHERE  status   = 'ACTIVE'
      AND  not_after < now();

    GET DIAGNOSTICS v_updated = ROW_COUNT;

    RAISE NOTICE 'mark_expired_certificates: transitioned % cert(s) ACTIVE → EXPIRED', v_updated;
END;
$$;

COMMENT ON PROCEDURE routify_cert.mark_expired_certificates(BOOLEAN) IS
    'Transitions ACTIVE certificates with not_after < now() to EXPIRED status. '
    'The application layer does this on access, but this procedure ensures DB consistency '
    'for certs that are uploaded and never queried again. Safe to run nightly.';

-------------------------------------------------------------------------------
-- PROCEDURE: routify_cert.purge_deleted_certificates
-- Purpose  : Hard-delete soft-deleted (status = DELETED) certificates older than
--            older_than_days.  The cert material is already unreadable (AES-GCM
--            encrypted, no key in the row), but removing the row frees space and
--            simplifies compliance evidence.
--
-- Usage    : CALL routify_cert.purge_deleted_certificates(90, true);   -- dry-run
--            CALL routify_cert.purge_deleted_certificates(90, false);  -- live
-------------------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE routify_cert.purge_deleted_certificates(
    older_than_days  INTEGER DEFAULT 90,
    p_dry_run        BOOLEAN DEFAULT true,
    p_tenant_id      UUID    DEFAULT NULL
)
LANGUAGE plpgsql
AS $$
DECLARE
    v_cutoff   TIMESTAMPTZ := now() - (older_than_days || ' days')::INTERVAL;
    v_count    INTEGER;
    v_deleted  INTEGER;
BEGIN
    IF older_than_days < 30 THEN
        RAISE EXCEPTION 'older_than_days must be >= 30 to prevent accidental loss (got %)', older_than_days;
    END IF;

    SELECT COUNT(*) INTO v_count
    FROM   routify_cert.stored_certificate
    WHERE  status    = 'DELETED'
      AND  updated_at < v_cutoff
      AND  (p_tenant_id IS NULL OR tenant_id = p_tenant_id);

    RAISE NOTICE 'purge_deleted_certificates: mode=% older_than_days=% tenant=% — % cert(s) eligible',
                 CASE WHEN p_dry_run THEN 'DRY-RUN' ELSE 'LIVE' END,
                 older_than_days,
                 COALESCE(p_tenant_id::TEXT, 'ALL'),
                 v_count;

    IF p_dry_run OR v_count = 0 THEN
        RETURN;
    END IF;

    DELETE FROM routify_cert.stored_certificate
    WHERE  status    = 'DELETED'
      AND  updated_at < v_cutoff
      AND  (p_tenant_id IS NULL OR tenant_id = p_tenant_id);

    GET DIAGNOSTICS v_deleted = ROW_COUNT;

    RAISE NOTICE 'purge_deleted_certificates: hard-deleted % certificate(s)', v_deleted;
END;
$$;

COMMENT ON PROCEDURE routify_cert.purge_deleted_certificates(INTEGER, BOOLEAN, UUID) IS
    'Hard-deletes soft-deleted (status=DELETED) certificates older than older_than_days (default 90). '
    'older_than_days must be >= 30. '
    'p_dry_run=true (default) prints the count without deleting. '
    'Pass p_tenant_id for GDPR/right-to-erasure scoped deletions.';

-------------------------------------------------------------------------------
-- PROCEDURE: routify_cert.cert_expiry_report
-- Purpose  : Print a structured certificate expiry report — used in the on-call
--            runbook and the weekly certificate review.
--
-- Usage    : CALL routify_cert.cert_expiry_report();         -- default 90-day horizon
--            CALL routify_cert.cert_expiry_report(30);       -- only show next 30 days
-------------------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE routify_cert.cert_expiry_report(
    warning_days  INTEGER DEFAULT 90
)
LANGUAGE plpgsql
AS $$
DECLARE
    v_horizon  TIMESTAMPTZ := now() + (warning_days || ' days')::INTERVAL;
    v_count    INTEGER;
    rec        RECORD;
BEGIN
    SELECT COUNT(*) INTO v_count
    FROM   routify_cert.stored_certificate
    WHERE  status    = 'ACTIVE'
      AND  expires_at IS NOT NULL
      AND  expires_at <= v_horizon;

    RAISE NOTICE '=== Certificate Expiry Report (next % days) ===', warning_days;
    RAISE NOTICE 'Active certs expiring before %: %', v_horizon, v_count;

    IF v_count = 0 THEN
        RAISE NOTICE 'No active certificates expiring in the next % days.', warning_days;
        RAISE NOTICE '=== End Report ===';
        RETURN;
    END IF;

    RAISE NOTICE '';
    RAISE NOTICE '%-36s  %-8s  %-40s  %-24s  %-10s',
                 'cert_id', 'days', 'alias', 'effective_logical_id', 'severity';
    RAISE NOTICE '%s', REPEAT('-', 130);

    FOR rec IN
        SELECT
            sc.id,
            sc.alias,
            sc.effective_logical_id,
            sc.expires_at,
            sc.tenant_id,
            ROUND(EXTRACT(EPOCH FROM (sc.expires_at - now())) / 86400.0, 1) AS days_left,
            CASE
                WHEN sc.expires_at <= now()                           THEN 'EXPIRED'
                WHEN sc.expires_at <= now() + INTERVAL '7 days'       THEN 'CRITICAL'
                WHEN sc.expires_at <= now() + INTERVAL '30 days'      THEN 'WARNING'
                ELSE 'WATCH'
            END AS severity
        FROM routify_cert.stored_certificate sc
        WHERE sc.status    = 'ACTIVE'
          AND sc.expires_at IS NOT NULL
          AND sc.expires_at <= v_horizon
        ORDER BY sc.expires_at ASC
    LOOP
        RAISE NOTICE '%-36s  %-8s  %-40s  %-24s  %-10s',
                     rec.id, rec.days_left, rec.alias,
                     COALESCE(rec.effective_logical_id, '(unmapped)'),
                     rec.severity;
    END LOOP;

    RAISE NOTICE '=== End Certificate Expiry Report ===';
END;
$$;

COMMENT ON PROCEDURE routify_cert.cert_expiry_report(INTEGER) IS
    'Prints a structured certificate expiry report via RAISE NOTICE. '
    'Shows all ACTIVE certs expiring within warning_days (default 90) with severity buckets. '
    'Use in on-call runbooks and weekly certificate review meetings.';

