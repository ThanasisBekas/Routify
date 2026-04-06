-- V2: Consolidate certificate gateway reference columns
--
-- Addresses H4: stored_certificate has three competing columns that all describe
-- the same concept ("which key does the gateway TLS registry use for this cert?"):
--
--   logical_id               VARCHAR(100)  -- was the TLS key; now internal only
--   gateway_tls_logical_id   VARCHAR(100)  -- legacy standalone binding
--   group_id → cert_group.logical_id       -- current authoritative key (via join)
--
-- This migration introduces a single computed/denormalised column effective_logical_id
-- that is maintained by an AFTER INSERT/UPDATE trigger on stored_certificate and on
-- cert_group (alias change).
--
-- The legacy columns are retained for backwards compatibility (existing data, old clients)
-- but are marked with comments indicating they are read-only / transitional.
-- A future V3 migration may DROP them once all consumers have migrated.

-- ─── 1. Add the canonical effective_logical_id column ─────────────────────────
ALTER TABLE routify_cert.stored_certificate
    ADD COLUMN IF NOT EXISTS effective_logical_id VARCHAR(100);

COMMENT ON COLUMN routify_cert.stored_certificate.effective_logical_id IS
    'The single canonical key used by the gateway TLS registry for this certificate. '
    'Computed: if group_id IS NOT NULL then cert_group.logical_id, else gateway_tls_logical_id. '
    'Maintained by trigger fn_refresh_effective_logical_id. '
    'Replaces the ambiguous triplet (logical_id, gateway_tls_logical_id, group.logical_id).';

COMMENT ON COLUMN routify_cert.stored_certificate.logical_id IS
    '[LEGACY — internal tracing only] Auto-generated cert-<uuid> assigned at upload. '
    'No longer the TLS registry key. Retained for backwards compatibility.';

COMMENT ON COLUMN routify_cert.stored_certificate.gateway_tls_logical_id IS
    '[LEGACY — superseded by group.logical_id when group_id IS NOT NULL] '
    'Standalone gateway TLS binding. Retained for backwards compatibility with ungrouped certs.';

-- ─── 2. Backfill existing rows ─────────────────────────────────────────────────
UPDATE routify_cert.stored_certificate sc
SET    effective_logical_id = CASE
           WHEN sc.group_id IS NOT NULL THEN (
               SELECT cg.logical_id
               FROM   routify_cert.cert_group cg
               WHERE  cg.id = sc.group_id
           )
           ELSE sc.gateway_tls_logical_id
       END;

-- ─── 3. Create trigger function to keep effective_logical_id in sync ───────────
CREATE OR REPLACE FUNCTION routify_cert.fn_refresh_effective_logical_id()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    -- Recompute when group_id or gateway_tls_logical_id changes on stored_certificate
    IF TG_TABLE_NAME = 'stored_certificate' THEN
        IF NEW.group_id IS NOT NULL THEN
            SELECT logical_id INTO NEW.effective_logical_id
            FROM   routify_cert.cert_group
            WHERE  id = NEW.group_id;
        ELSE
            NEW.effective_logical_id := NEW.gateway_tls_logical_id;
        END IF;
    END IF;

    -- When cert_group.logical_id is updated, propagate to all member certificates
    IF TG_TABLE_NAME = 'cert_group' AND NEW.logical_id IS DISTINCT FROM OLD.logical_id THEN
        UPDATE routify_cert.stored_certificate
        SET    effective_logical_id = NEW.logical_id
        WHERE  group_id = NEW.id;
    END IF;

    RETURN NEW;
END;
$$;

-- ─── 4. Attach trigger to stored_certificate ──────────────────────────────────
DROP TRIGGER IF EXISTS trg_cert_effective_logical_id
    ON routify_cert.stored_certificate;

CREATE TRIGGER trg_cert_effective_logical_id
    BEFORE INSERT OR UPDATE OF group_id, gateway_tls_logical_id
    ON routify_cert.stored_certificate
    FOR EACH ROW
    EXECUTE FUNCTION routify_cert.fn_refresh_effective_logical_id();

-- ─── 5. Attach trigger to cert_group (propagate logical_id rename) ────────────
DROP TRIGGER IF EXISTS trg_cert_group_propagate_logical_id
    ON routify_cert.cert_group;

CREATE TRIGGER trg_cert_group_propagate_logical_id
    AFTER UPDATE OF logical_id
    ON routify_cert.cert_group
    FOR EACH ROW
    EXECUTE FUNCTION routify_cert.fn_refresh_effective_logical_id();

-- ─── 6. Add index on effective_logical_id for fast gateway lookups ─────────────
CREATE INDEX IF NOT EXISTS idx_cert_effective_logical_id
    ON routify_cert.stored_certificate (effective_logical_id)
    WHERE effective_logical_id IS NOT NULL;

COMMENT ON FUNCTION routify_cert.fn_refresh_effective_logical_id() IS
    'Keeps stored_certificate.effective_logical_id in sync with its group logical_id '
    'or standalone gateway_tls_logical_id. Fires BEFORE INSERT/UPDATE on stored_certificate '
    'and AFTER UPDATE OF logical_id on cert_group.';

