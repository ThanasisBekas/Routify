-- V12: Scope the active-route uniqueness constraint by environment.
--
-- Problem: The partial unique index uq_route_path_methods_tenant_active enforces
-- uniqueness of (tenant_id, path_pattern, methods) across ALL environments. This
-- blocks activating a STAGING route when a PRODUCTION route with the same path/methods
-- already exists — breaking the staging → activate → test → promote workflow.
--
-- Fix: Include `environment` in the index so STAGING and PRODUCTION routes can both
-- be ACTIVE with the same (tenant_id, path_pattern, methods). The gateway matches
-- STAGING routes only when the request carries X-Route-Environment: STAGING, so there
-- is no routing ambiguity.

-- Drop the V10 index (tenant_id, path_pattern, methods) WHERE ACTIVE AND NOT canary
DROP INDEX IF EXISTS routify.uq_route_path_methods_tenant_active;

-- Recreate with environment included in the composite key
CREATE UNIQUE INDEX uq_route_path_methods_tenant_active
    ON routify.route (tenant_id, path_pattern, methods, environment)
    WHERE status = 'ACTIVE' AND is_canary = FALSE;

