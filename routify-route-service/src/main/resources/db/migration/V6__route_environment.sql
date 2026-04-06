-- V6: Add environment column to routes for staging/production promotion flow.
-- Default PRODUCTION ensures all existing routes continue to work unchanged.

-- Add environment column with default PRODUCTION (non-breaking)
ALTER TABLE routify.route
    ADD COLUMN environment VARCHAR(20) NOT NULL DEFAULT 'PRODUCTION';

-- Index for gateway snapshot query filtering
CREATE INDEX idx_route_environment ON routify.route(tenant_id, environment, status);

-- Update unique constraint: same (name, tenant) can now exist in different environments
ALTER TABLE routify.route DROP CONSTRAINT IF EXISTS uq_route_name_tenant;
ALTER TABLE routify.route ADD CONSTRAINT uq_route_name_tenant_env
    UNIQUE (name, tenant_id, environment);

