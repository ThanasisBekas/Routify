-- V10: Fix unique constraint to allow canary routes.
--
-- The partial unique index uq_route_path_methods_tenant_active blocks canary routes from being
-- activated because they share the same (tenant_id, path_pattern, methods) as the primary route.
-- This migration adds an explicit `is_canary` flag and rebuilds the index to exclude canary rows.

-- 1. Add the is_canary column (defaults to false for all existing routes)
ALTER TABLE routify.route
    ADD COLUMN is_canary BOOLEAN NOT NULL DEFAULT FALSE;

-- 2. Backfill: mark any existing canary routes (identified by having canaryPrimaryRouteId in extra_config)
UPDATE routify.route
   SET is_canary = TRUE
 WHERE extra_config IS NOT NULL
   AND extra_config->>'canaryPrimaryRouteId' IS NOT NULL;

-- 3. Drop the old unique index that doesn't account for canary routes
DROP INDEX IF EXISTS routify.uq_route_path_methods_tenant_active;

-- 4. Recreate the unique index excluding canary routes
--    Canary routes legitimately share (tenant_id, path_pattern, methods) with their primary.
CREATE UNIQUE INDEX uq_route_path_methods_tenant_active
    ON routify.route (tenant_id, path_pattern, methods)
    WHERE status = 'ACTIVE' AND is_canary = FALSE;

-- 5. Index for efficient canary lookups
CREATE INDEX idx_route_is_canary ON routify.route (is_canary) WHERE is_canary = TRUE;

