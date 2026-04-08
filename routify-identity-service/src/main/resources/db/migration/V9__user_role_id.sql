-- ============================================================================
-- V9: Add role_id FK to app_user for custom role assignment (Initiative 04)
-- ============================================================================

-- Add role_id column (nullable initially for backfill)
ALTER TABLE routify_identity.app_user
    ADD COLUMN role_id UUID REFERENCES routify_identity.role_definition(id);

-- Backfill: map existing role enum values → built-in role_definition UUIDs
UPDATE routify_identity.app_user SET role_id = '00000000-0000-0000-0000-000000000001' WHERE role = 'SUPER_ADMIN';
UPDATE routify_identity.app_user SET role_id = '00000000-0000-0000-0000-000000000002' WHERE role = 'TENANT_ADMIN';
UPDATE routify_identity.app_user SET role_id = '00000000-0000-0000-0000-000000000003' WHERE role = 'OPERATOR';
UPDATE routify_identity.app_user SET role_id = '00000000-0000-0000-0000-000000000004' WHERE role = 'VIEWER';

-- Set NOT NULL after backfill
ALTER TABLE routify_identity.app_user ALTER COLUMN role_id SET NOT NULL;

-- Index for FK performance
CREATE INDEX idx_app_user_role_id ON routify_identity.app_user(role_id);

