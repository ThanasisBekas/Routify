-- V9: Allow re-creating STAGING routes after promotion (which archives the previous staging).
--
-- The full unique constraint uq_route_name_tenant_env blocks creating a new STAGING route
-- when an ARCHIVED one with the same (name, tenant_id, environment) already exists.
-- Replace it with a partial unique index that excludes ARCHIVED rows.

ALTER TABLE routify.route DROP CONSTRAINT IF EXISTS uq_route_name_tenant_env;

CREATE UNIQUE INDEX uq_route_name_tenant_env_live
    ON routify.route (name, tenant_id, environment)
    WHERE status != 'ARCHIVED';

